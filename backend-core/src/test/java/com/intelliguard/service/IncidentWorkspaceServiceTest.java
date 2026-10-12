package com.intelliguard.service;

import com.intelliguard.entity.Incident;
import com.intelliguard.entity.IncidentNote;
import com.intelliguard.entity.enums.IncidentStatus;
import com.intelliguard.repository.IncidentNoteRepository;
import com.intelliguard.repository.IncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Notes and acknowledgement: validation, MERGED/RESOLVED rejection, idempotence, the
// NOTE_ADDED / ACKNOWLEDGED events, and the retry when the correlation job wrote concurrently.
class IncidentWorkspaceServiceTest {

    private final IncidentRepository incidents = mock(IncidentRepository.class);
    private final IncidentNoteRepository notes = mock(IncidentNoteRepository.class);
    private final IncidentWorkspaceService service = new IncidentWorkspaceService(incidents, notes,
            new TransactionTemplate(mock(PlatformTransactionManager.class)));
    private final List<IncidentNote> saved = new ArrayList<>();
    private Incident incident;

    @BeforeEach
    void setUp() {
        incident = Incident.builder().id(1L).title("order-db + 1 related service").status(IncidentStatus.OPEN)
                .events(new ArrayList<>()).build();
        // `incident` is the stored row: every load returns a fresh copy and only a save changes it,
        // like a real transaction (a rolled-back attempt leaves no trace).
        when(incidents.findById(1L)).thenAnswer(inv -> Optional.of(copy(incident)));
        when(incidents.findById(404L)).thenReturn(Optional.empty());
        when(incidents.save(any())).thenAnswer(inv -> {
            incident = copy(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(notes.save(any())).thenAnswer(inv -> {
            IncidentNote n = inv.getArgument(0);
            n.setId(saved.size() + 10L);
            saved.add(n);
            return n;
        });
    }

    @Test
    void aNoteIsTrimmedStoredWithItsAuthorAndRecordedAsAnEvent() {
        IncidentNote note = service.addNote(1L, "  order-db disk is full  ", "alice");

        assertThat(note.getText()).isEqualTo("order-db disk is full");
        assertThat(note.getAuthor()).isEqualTo("alice");
        assertThat(note.getIncidentId()).isEqualTo(1L);
        assertThat(incident.getEvents()).singleElement().satisfies(e -> {
            assertThat(e.get("type")).isEqualTo("NOTE_ADDED");
            assertThat(e.get("by")).isEqualTo("alice");
            assertThat(e.get("noteId")).isEqualTo(note.getId());
        });
    }

    @Test
    void blankOrTooLongNotesAreRejected() {
        for (String bad : new String[]{null, "", "   \n\t "}) {
            assertStatus(() -> service.addNote(1L, bad, "alice"), HttpStatus.BAD_REQUEST);
        }
        assertStatus(() -> service.addNote(1L, "x".repeat(2001), "alice"), HttpStatus.BAD_REQUEST);
        assertThat(saved).isEmpty();

        service.addNote(1L, "  " + "x".repeat(2000) + "  ", "alice"); // 2000 after trimming is fine
        assertThat(saved).hasSize(1);
    }

    @Test
    void unknownIncidentIsNotFound() {
        assertStatus(() -> service.addNote(404L, "hello", "alice"), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.acknowledge(404L, "alice"), HttpStatus.NOT_FOUND);
    }

    @Test
    void aMergedIncidentRejectsNotesAndAcknowledgement() {
        incident.setStatus(IncidentStatus.MERGED);
        incident.setMergedIntoId(7L);

        assertStatus(() -> service.addNote(1L, "hello", "alice"), HttpStatus.CONFLICT);
        assertStatus(() -> service.acknowledge(1L, "alice"), HttpStatus.CONFLICT);
        verify(notes, never()).save(any());
        verify(incidents, never()).save(any());
    }

    @Test
    void acknowledgeSetsInvestigatingOnceAndIsIdempotent() {
        Incident first = service.acknowledge(1L, "alice");
        Incident again = service.acknowledge(1L, "bob");

        assertThat(again.getStatus()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(again.getAcknowledgedBy()).isEqualTo("alice");
        assertThat(again.getAcknowledgedAt()).isEqualTo(first.getAcknowledgedAt()).isNotNull();
        assertThat(incident.getEvents()).singleElement().satisfies(e -> {
            assertThat(e.get("type")).isEqualTo("ACKNOWLEDGED");
            assertThat(e.get("by")).isEqualTo("alice");
        });
        verify(incidents, times(1)).save(any());
    }

    @Test
    void aResolvedIncidentCannotBeAcknowledgedButStillTakesNotes() {
        incident.setStatus(IncidentStatus.RESOLVED);
        assertStatus(() -> service.acknowledge(1L, "alice"), HttpStatus.CONFLICT);
        assertThat(service.addNote(1L, "post-incident note", "alice").getText()).isEqualTo("post-incident note");
    }

    @Test
    void aConcurrentJobWriteIsRetriedInsteadOfOverwritten() {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new ObjectOptimisticLockingFailureException(Incident.class, 1L);
            }
            incident = copy(inv.getArgument(0));
            return inv.getArgument(0);
        }).when(incidents).save(any());

        assertThat(service.acknowledge(1L, "alice").getStatus()).isEqualTo(IncidentStatus.INVESTIGATING);
        assertThat(calls.get()).isEqualTo(2);
    }

    private static Incident copy(Incident i) {
        return Incident.builder().id(i.getId()).title(i.getTitle()).status(i.getStatus()).mergedIntoId(i.getMergedIntoId())
                .acknowledgedBy(i.getAcknowledgedBy()).acknowledgedAt(i.getAcknowledgedAt())
                .events(new ArrayList<>(i.getEvents())).build();
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }
}
