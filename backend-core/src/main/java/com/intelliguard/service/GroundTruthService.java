package com.intelliguard.service;

import com.intelliguard.dto.GroundTruthEventRequest;
import com.intelliguard.entity.GroundTruthEvent;
import com.intelliguard.entity.enums.SignalType;
import com.intelliguard.repository.GroundTruthEventRepository;
import com.intelliguard.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

// Stores and serves the simulator's injection labels (evaluation ground truth). Nothing here
// detects anything - detections belong in Anomaly, which this never reads or writes.
@Service
@RequiredArgsConstructor
public class GroundTruthService {

    private final GroundTruthEventRepository groundTruthEventRepository;
    private final ServiceRepository serviceRepository;

    public GroundTruthEvent record(GroundTruthEventRequest request) {
        if (request.getEndTime().isBefore(request.getStartTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "endTime must not be before startTime");
        }
        if (request.getSignalType() == SignalType.METRIC && request.getMetricType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "metricType is required for METRIC labels");
        }
        if (request.getSignalType() != SignalType.METRIC && request.getMetricType() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "metricType must be omitted for " + request.getSignalType() + " labels");
        }
        // Same convention as telemetry/security-event ingestion: an unknown service is a 404.
        if (!serviceRepository.existsById(request.getServiceId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Service " + request.getServiceId() + " not found");
        }

        GroundTruthEvent event = GroundTruthEvent.builder()
                .runId(request.getRunId())
                .seed(request.getSeed())
                .serviceId(request.getServiceId())
                .signalType(request.getSignalType())
                .metricType(request.getMetricType())
                .scenarioType(request.getScenarioType())
                .difficulty(request.getDifficulty())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .params(request.getParams())
                .build();

        return groundTruthEventRepository.save(event);
    }

    // Both filters optional; a blank runId (e.g. "?runId=") means "any run", not "runId equals ''".
    public List<GroundTruthEvent> find(String runId, Long serviceId) {
        String normalizedRunId = runId == null || runId.isBlank() ? null : runId;
        return groundTruthEventRepository.findFiltered(normalizedRunId, serviceId);
    }
}
