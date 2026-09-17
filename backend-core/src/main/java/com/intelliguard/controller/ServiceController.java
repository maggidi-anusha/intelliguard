package com.intelliguard.controller;

import com.intelliguard.dto.ServiceRequest;
import com.intelliguard.entity.Service;
import com.intelliguard.service.ServiceRegistryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceRegistryService serviceRegistryService;

    @PostMapping
    public ResponseEntity<Service> create(@Valid @RequestBody ServiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(serviceRegistryService.create(request));
    }

    @GetMapping
    public List<Service> getAll() {
        return serviceRegistryService.getAll();
    }

    @GetMapping("/{id}")
    public Service getById(@PathVariable Long id) {
        return serviceRegistryService.getById(id);
    }

    @PutMapping("/{id}")
    public Service update(@PathVariable Long id, @Valid @RequestBody ServiceRequest request) {
        return serviceRegistryService.update(id, request);
    }
}
