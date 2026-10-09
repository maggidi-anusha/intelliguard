package com.intelliguard.service;

import com.intelliguard.dto.GroundTruthEventRequest;
import com.intelliguard.entity.GroundTruthEvent;
import com.intelliguard.entity.enums.Difficulty;
import com.intelliguard.entity.enums.MetricType;
import com.intelliguard.entity.enums.ScenarioType;
import com.intelliguard.entity.enums.SignalType;
import com.intelliguard.repository.GroundTruthEventRepository;
import com.intelliguard.repository.ServiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GroundTruthServiceTest {

    @Mock
    private GroundTruthEventRepository groundTruthEventRepository;
    @Mock
    private ServiceRepository serviceRepository;

    @InjectMocks
    private GroundTruthService groundTruthService;

    private static final Instant START = Instant.parse("2026-10-09T10:00:00Z");
    private static final Instant END = Instant.parse("2026-10-09T10:02:00Z");

    private GroundTruthEventRequest request(SignalType signalType, MetricType metricType) {
        GroundTruthEventRequest request = new GroundTruthEventRequest();
        request.setRunId("live-demo-20261009T100000Z-s42");
        request.setSeed(42L);
        request.setServiceId(4L);
        request.setSignalType(signalType);
        request.setMetricType(metricType);
        request.setScenarioType(ScenarioType.SPIKE);
        request.setDifficulty(Difficulty.EASY);
        request.setStartTime(START);
        request.setEndTime(END);
        request.setParams(Map.of("episode_id", "demo-2"));
        return request;
    }

    private static HttpStatus statusOf(Throwable e) {
        return HttpStatus.valueOf(((ResponseStatusException) e).getStatusCode().value());
    }

    @Test
    void record_validMetricLabel_isSavedWithAllFields() {
        when(serviceRepository.existsById(4L)).thenReturn(true);
        when(groundTruthEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        groundTruthService.record(request(SignalType.METRIC, MetricType.ERROR_RATE));

        ArgumentCaptor<GroundTruthEvent> saved = ArgumentCaptor.forClass(GroundTruthEvent.class);
        verify(groundTruthEventRepository).save(saved.capture());
        GroundTruthEvent event = saved.getValue();
        assertThat(event.getRunId()).isEqualTo("live-demo-20261009T100000Z-s42");
        assertThat(event.getSeed()).isEqualTo(42L);
        assertThat(event.getMetricType()).isEqualTo(MetricType.ERROR_RATE);
        assertThat(event.getStartTime()).isEqualTo(START);
        assertThat(event.getEndTime()).isEqualTo(END);
        assertThat(event.getParams()).containsEntry("episode_id", "demo-2");
    }

    @Test
    void record_securityLabelWithoutMetricType_isSaved() {
        when(serviceRepository.existsById(4L)).thenReturn(true);

        groundTruthService.record(request(SignalType.SECURITY, null));

        verify(groundTruthEventRepository).save(any());
    }

    @Test
    void record_metricLabelWithoutMetricType_isBadRequest() {
        assertThatThrownBy(() -> groundTruthService.record(request(SignalType.METRIC, null)))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(groundTruthEventRepository, never()).save(any());
    }

    @Test
    void record_securityLabelWithMetricType_isBadRequest() {
        assertThatThrownBy(() -> groundTruthService.record(request(SignalType.SECURITY, MetricType.CPU)))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(groundTruthEventRepository, never()).save(any());
    }

    @Test
    void record_endBeforeStart_isBadRequest() {
        GroundTruthEventRequest request = request(SignalType.METRIC, MetricType.CPU);
        request.setEndTime(START.minusSeconds(5));

        assertThatThrownBy(() -> groundTruthService.record(request))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(groundTruthEventRepository, never()).save(any());
    }

    @Test
    void record_unknownService_isNotFound() {
        when(serviceRepository.existsById(4L)).thenReturn(false);

        assertThatThrownBy(() -> groundTruthService.record(request(SignalType.METRIC, MetricType.CPU)))
                .satisfies(e -> assertThat(statusOf(e)).isEqualTo(HttpStatus.NOT_FOUND));
        verify(groundTruthEventRepository, never()).save(any());
    }

    @Test
    void find_blankRunIdMeansAnyRun() {
        when(groundTruthEventRepository.findFiltered(null, 4L)).thenReturn(List.of());

        assertThat(groundTruthService.find("  ", 4L)).isEmpty();
        verify(groundTruthEventRepository).findFiltered(null, 4L);
    }
}
