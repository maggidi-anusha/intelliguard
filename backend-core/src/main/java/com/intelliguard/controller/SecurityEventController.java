package com.intelliguard.controller;

import com.intelliguard.dto.SecurityEventRequest;
import com.intelliguard.entity.SecurityEvent;
import com.intelliguard.service.SecurityEventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/security-events")
@RequiredArgsConstructor
public class SecurityEventController {

    private final SecurityEventService securityEventService;

    @PostMapping
    public ResponseEntity<SecurityEvent> record(@Valid @RequestBody SecurityEventRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(securityEventService.record(request));
    }
}
