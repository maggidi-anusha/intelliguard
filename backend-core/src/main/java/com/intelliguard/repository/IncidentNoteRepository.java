package com.intelliguard.repository;

import com.intelliguard.entity.IncidentNote;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface IncidentNoteRepository extends JpaRepository<IncidentNote, Long> {

    // Newest first, bounded by the Pageable (the detail view reverses it to oldest first).
    List<IncidentNote> findByIncidentIdOrderByCreatedAtDescIdDesc(Long incidentId, Pageable pageable);

    long countByIncidentId(Long incidentId);

    // Retention: notes go with their incident.
    @Transactional
    long deleteByIncidentIdIn(Collection<Long> incidentIds);
}
