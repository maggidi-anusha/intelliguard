package com.intelliguard.dto;

import com.intelliguard.entity.enums.MetricType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
public class MetricRequest {

    @NotNull
    private MetricType metricType;

    @NotNull
    private Double value;

    // Optional - defaults to server receipt time when omitted.
    private Instant timestamp;
}
