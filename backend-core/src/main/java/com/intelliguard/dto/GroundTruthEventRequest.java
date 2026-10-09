package com.intelliguard.dto;

import com.intelliguard.entity.enums.Difficulty;
import com.intelliguard.entity.enums.MetricType;
import com.intelliguard.entity.enums.ScenarioType;
import com.intelliguard.entity.enums.SignalType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.Map;

@Getter
@Setter
public class GroundTruthEventRequest {

    @NotBlank
    private String runId;

    @NotNull
    private Long seed;

    @NotNull
    private Long serviceId;

    @NotNull
    private SignalType signalType;

    // Required for METRIC labels, must be absent otherwise (checked in GroundTruthService).
    private MetricType metricType;

    @NotNull
    private ScenarioType scenarioType;

    @NotNull
    private Difficulty difficulty;

    @NotNull
    private Instant startTime;

    @NotNull
    private Instant endTime;

    private Map<String, Object> params;
}
