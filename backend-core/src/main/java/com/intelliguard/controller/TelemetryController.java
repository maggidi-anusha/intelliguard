package com.intelliguard.controller;

import com.intelliguard.dto.LogRequest;
import com.intelliguard.dto.MetricRequest;
import com.intelliguard.entity.LogRecord;
import com.intelliguard.entity.MetricRecord;
import com.intelliguard.service.TelemetryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/services/{serviceId}")
@RequiredArgsConstructor
public class TelemetryController {

    private final TelemetryService telemetryService;

    @PostMapping("/metrics")
    public ResponseEntity<MetricRecord> recordMetric(
            @PathVariable Long serviceId,
            @Valid @RequestBody MetricRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(telemetryService.recordMetric(serviceId, request));
    }

    @PostMapping("/logs")
    public ResponseEntity<LogRecord> recordLog(
            @PathVariable Long serviceId,
            @Valid @RequestBody LogRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(telemetryService.recordLog(serviceId, request));
    }

    @GetMapping("/metrics")
    public List<MetricRecord> getRecentMetrics(@PathVariable Long serviceId) {
        return telemetryService.getRecentMetrics(serviceId);
    }

    @GetMapping("/logs")
    public List<LogRecord> getRecentLogs(@PathVariable Long serviceId) {
        return telemetryService.getRecentLogs(serviceId);
    }
}
