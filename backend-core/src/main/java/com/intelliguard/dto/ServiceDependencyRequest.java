package com.intelliguard.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ServiceDependencyRequest {

    // This service...
    @NotNull
    private Long serviceId;

    // ...calls / depends on this one.
    @NotNull
    private Long dependsOnServiceId;
}
