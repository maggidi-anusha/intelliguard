package com.intelliguard.dto;

import com.intelliguard.entity.enums.LogLevel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
public class LogRequest {

    @NotNull
    private LogLevel level;

    @NotBlank
    private String message;

    private Instant timestamp;

    private Map<String, Object> metadata;
}
