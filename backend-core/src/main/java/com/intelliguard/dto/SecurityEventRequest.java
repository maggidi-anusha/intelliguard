package com.intelliguard.dto;

import com.intelliguard.entity.enums.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
public class SecurityEventRequest {

    // Nullable - not every security event (e.g. a network-wide port scan) ties to one service.
    private Long serviceId;

    @NotBlank
    private String eventType;

    @NotNull
    private Severity severity;

    private Instant timestamp;

    private Map<String, Object> details;
}
