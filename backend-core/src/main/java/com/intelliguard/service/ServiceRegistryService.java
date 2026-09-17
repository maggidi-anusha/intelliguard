package com.intelliguard.service;

import com.intelliguard.dto.ServiceRequest;
import com.intelliguard.entity.Service;
import com.intelliguard.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class ServiceRegistryService {

    private final ServiceRepository serviceRepository;

    public Service create(ServiceRequest request) {
        if (serviceRepository.existsByName(request.getName())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A service named '" + request.getName() + "' already exists");
        }

        Service service = Service.builder()
                .name(request.getName())
                .type(request.getType())
                .criticality(request.getCriticality())
                .hostname(request.getHostname())
                .dependsOnServiceId(request.getDependsOnServiceId())
                .build();

        return serviceRepository.save(service);
    }

    public List<Service> getAll() {
        return serviceRepository.findAll();
    }

    public Service getById(Long id) {
        return serviceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Service " + id + " not found"));
    }

    public Service update(Long id, ServiceRequest request) {
        Service service = getById(id);

        serviceRepository.findByName(request.getName())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "A service named '" + request.getName() + "' already exists");
                });

        service.setName(request.getName());
        service.setType(request.getType());
        service.setCriticality(request.getCriticality());
        service.setHostname(request.getHostname());
        service.setDependsOnServiceId(request.getDependsOnServiceId());

        return serviceRepository.save(service);
    }
}
