package com.intelliguard.dto;

import com.intelliguard.entity.enums.Criticality;
import com.intelliguard.entity.enums.ServiceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ServiceRequest {

    @NotBlank
    private String name;

    @NotNull
    private ServiceType type;

    @NotNull
    private Criticality criticality;

    private String hostname;

    private Long dependsOnServiceId;
}
