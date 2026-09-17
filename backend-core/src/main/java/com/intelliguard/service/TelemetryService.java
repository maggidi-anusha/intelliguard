package com.intelliguard.service;

import com.intelliguard.dto.LogRequest;
import com.intelliguard.dto.MetricRequest;
import com.intelliguard.entity.LogRecord;
import com.intelliguard.entity.MetricRecord;
import com.intelliguard.repository.LogRecordRepository;
import com.intelliguard.repository.MetricRecordRepository;
import com.intelliguard.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class TelemetryService {

    private final MetricRecordRepository metricRecordRepository;
    private final LogRecordRepository logRecordRepository;
    private final ServiceRepository serviceRepository;

    public MetricRecord recordMetric(Long serviceId, MetricRequest request) {
        ensureServiceExists(serviceId);

        MetricRecord record = MetricRecord.builder()
                .serviceId(serviceId)
                .metricType(request.getMetricType())
                .value(request.getValue())
                .timestamp(request.getTimestamp() != null ? request.getTimestamp() : Instant.now())
                .build();

        return metricRecordRepository.save(record);
    }

    public LogRecord recordLog(Long serviceId, LogRequest request) {
        ensureServiceExists(serviceId);

        LogRecord record = LogRecord.builder()
                .serviceId(serviceId)
                .level(request.getLevel())
                .message(request.getMessage())
                .timestamp(request.getTimestamp() != null ? request.getTimestamp() : Instant.now())
                .metadata(request.getMetadata())
                .build();

        return logRecordRepository.save(record);
    }

    private void ensureServiceExists(Long serviceId) {
        if (!serviceRepository.existsById(serviceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service " + serviceId + " not found");
        }
    }
}
