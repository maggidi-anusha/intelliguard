package com.intelliguard.service;

import com.intelliguard.dto.SecurityEventRequest;
import com.intelliguard.entity.SecurityEvent;
import com.intelliguard.repository.SecurityEventRepository;
import com.intelliguard.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SecurityEventService {

    private final SecurityEventRepository securityEventRepository;
    private final ServiceRepository serviceRepository;

    public SecurityEvent record(SecurityEventRequest request) {
        if (request.getServiceId() != null && !serviceRepository.existsById(request.getServiceId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Service " + request.getServiceId() + " not found");
        }

        SecurityEvent event = SecurityEvent.builder()
                .serviceId(request.getServiceId())
                .eventType(request.getEventType())
                .severity(request.getSeverity())
                .timestamp(request.getTimestamp() != null ? request.getTimestamp() : Instant.now())
                .details(request.getDetails())
                .build();

        return securityEventRepository.save(event);
    }

    public List<SecurityEvent> getRecent() {
        return securityEventRepository.findTop50ByOrderByTimestampDesc();
    }
}
