package com.intelliguard.controller;

import com.intelliguard.entity.LogRecord;
import com.intelliguard.entity.enums.LogLevel;
import com.intelliguard.service.TelemetryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Separate from TelemetryController on purpose: that controller is scoped under
// /api/services/{serviceId}, which doesn't fit a global, optionally-filtered query.
@RestController
@RequiredArgsConstructor
public class LogController {

    private final TelemetryService telemetryService;

    @GetMapping("/api/logs")
    public List<LogRecord> getRecent(
            @RequestParam(required = false) Long serviceId,
            @RequestParam(required = false) LogLevel level) {
        return telemetryService.getRecent(serviceId, level);
    }
}
