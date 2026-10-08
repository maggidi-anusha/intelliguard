package com.intelliguard.service;

import com.intelliguard.dto.LogRequest;
import com.intelliguard.dto.MetricRequest;
import com.intelliguard.entity.LogRecord;
import com.intelliguard.entity.MetricRecord;
import com.intelliguard.entity.enums.LogLevel;
import com.intelliguard.repository.LogRecordRepository;
import com.intelliguard.repository.MetricRecordRepository;
import com.intelliguard.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TelemetryService {

    // Bounds the global /api/logs view - a cross-service query has no natural per-service
    // limit like the existing top-50, so this is capped explicitly instead of unbounded.
    private static final int GLOBAL_LOGS_LIMIT = 200;

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

    public List<MetricRecord> getRecentMetrics(Long serviceId) {
        ensureServiceExists(serviceId);
        return metricRecordRepository.findTop50ByServiceIdOrderByTimestampDesc(serviceId);
    }

    public List<LogRecord> getRecentLogs(Long serviceId) {
        ensureServiceExists(serviceId);
        return logRecordRepository.findTop50ByServiceIdOrderByTimestampDesc(serviceId);
    }

    // Global, cross-service log view backing the dedicated Logs page - both filters are
    // optional (null means "any").
    public List<LogRecord> getRecent(Long serviceId, LogLevel level) {
        if (serviceId != null) {
            ensureServiceExists(serviceId);
        }
        return logRecordRepository.findRecent(serviceId, level, PageRequest.of(0, GLOBAL_LOGS_LIMIT));
    }

    private void ensureServiceExists(Long serviceId) {
        if (!serviceRepository.existsById(serviceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service " + serviceId + " not found");
        }
    }
}
