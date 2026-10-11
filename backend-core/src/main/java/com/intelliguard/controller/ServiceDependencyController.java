package com.intelliguard.controller;

import com.intelliguard.dto.ServiceDependencyRequest;
import com.intelliguard.entity.ServiceDependency;
import com.intelliguard.service.ServiceDependencyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// The service dependency graph. Read: any authenticated role; write: USER or ADMIN, the same
// rule as service writes (see SecurityConfig).
@RestController
@RequestMapping("/api/service-dependencies")
@RequiredArgsConstructor
public class ServiceDependencyController {

    private final ServiceDependencyService dependencyService;

    @GetMapping
    public List<ServiceDependency> getAll() {
        return dependencyService.getAll();
    }

    @PostMapping
    public ResponseEntity<ServiceDependency> create(@Valid @RequestBody ServiceDependencyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(dependencyService.create(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        dependencyService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
