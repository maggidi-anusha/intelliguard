package com.intelliguard.service;

import com.intelliguard.dto.ServiceDependencyRequest;
import com.intelliguard.entity.Service;
import com.intelliguard.entity.ServiceDependency;
import com.intelliguard.repository.ServiceDependencyRepository;
import com.intelliguard.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// The service dependency graph (service_dependencies). Every edge is validated the same way,
// whether it comes from the API, a service write's legacy dependsOnServiceId, or the startup
// copy: both services must exist (404), no self-loops (400), no duplicates (409), and no edge
// that would close a cycle (400) - the graph stays a DAG, which the root-cause ranking assumes.
// Writes are serialized (synchronized) so two concurrent POSTs can't create a cycle together.
@Slf4j
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class ServiceDependencyService {

    private final ServiceDependencyRepository dependencyRepository;
    private final ServiceRepository serviceRepository;

    public List<ServiceDependency> getAll() {
        return dependencyRepository.findAllByOrderByServiceIdAscDependsOnServiceIdAsc();
    }

    public synchronized ServiceDependency create(ServiceDependencyRequest request) {
        Long serviceId = request.getServiceId();
        Long dependsOn = request.getDependsOnServiceId();
        Optional<ResponseStatusException> invalid = validate(serviceId, dependsOn);
        if (invalid.isPresent()) {
            throw invalid.get();
        }
        try {
            return dependencyRepository.saveAndFlush(
                    ServiceDependency.builder().serviceId(serviceId).dependsOnServiceId(dependsOn).build());
        } catch (DataIntegrityViolationException e) {
            throw duplicate(serviceId, dependsOn); // lost a race with an identical insert
        }
    }

    public synchronized void delete(Long id) {
        if (!dependencyRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Service dependency " + id + " not found");
        }
        dependencyRepository.deleteById(id);
    }

    /**
     * Copies every service's legacy dependsOnServiceId into service_dependencies. Idempotent:
     * edges that already exist are skipped, as are invalid ones (logged). Returns the number added.
     */
    public synchronized int copyLegacyDependencies() {
        int added = 0;
        for (Service service : serviceRepository.findAll()) {
            if (service.getDependsOnServiceId() != null && addIfValid(service.getId(), service.getDependsOnServiceId())) {
                added++;
            }
        }
        return added;
    }

    /** Keeps a service write's legacy dependsOnServiceId reflected in the graph (add only, never fails the write). */
    public synchronized void mirrorLegacyDependency(Service service) {
        if (service.getId() != null && service.getDependsOnServiceId() != null) {
            addIfValid(service.getId(), service.getDependsOnServiceId());
        }
    }

    private boolean addIfValid(Long serviceId, Long dependsOn) {
        Optional<ResponseStatusException> invalid = validate(serviceId, dependsOn);
        if (invalid.isPresent()) {
            if (invalid.get().getStatusCode() != HttpStatus.CONFLICT) {
                log.warn("Legacy dependency {} -> {} not copied: {}", serviceId, dependsOn, invalid.get().getReason());
            }
            return false;
        }
        try {
            dependencyRepository.saveAndFlush(
                    ServiceDependency.builder().serviceId(serviceId).dependsOnServiceId(dependsOn).build());
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    private Optional<ResponseStatusException> validate(Long serviceId, Long dependsOn) {
        if (serviceId.equals(dependsOn)) {
            return Optional.of(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A service cannot depend on itself (service " + serviceId + ")"));
        }
        for (Long id : List.of(serviceId, dependsOn)) {
            if (!serviceRepository.existsById(id)) {
                return Optional.of(new ResponseStatusException(HttpStatus.NOT_FOUND, "Service " + id + " not found"));
            }
        }
        if (dependencyRepository.existsByServiceIdAndDependsOnServiceId(serviceId, dependsOn)) {
            return Optional.of(duplicate(serviceId, dependsOn));
        }
        if (reachable(dependsOn, serviceId)) {
            return Optional.of(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Dependency " + serviceId + " -> " + dependsOn + " would create a cycle: service " + dependsOn
                            + " already depends (directly or transitively) on service " + serviceId));
        }
        return Optional.empty();
    }

    /** Whether `to` can be reached from `from` by following existing dependsOn edges. */
    private boolean reachable(Long from, Long to) {
        Map<Long, Set<Long>> out = new HashMap<>();
        for (ServiceDependency d : dependencyRepository.findAll()) {
            out.computeIfAbsent(d.getServiceId(), k -> new HashSet<>()).add(d.getDependsOnServiceId());
        }
        Set<Long> seen = new HashSet<>();
        Deque<Long> stack = new ArrayDeque<>(List.of(from));
        while (!stack.isEmpty()) {
            Long node = stack.pop();
            if (node.equals(to)) {
                return true;
            }
            if (seen.add(node)) {
                stack.addAll(out.getOrDefault(node, Set.of()));
            }
        }
        return false;
    }

    private static ResponseStatusException duplicate(Long serviceId, Long dependsOn) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "Dependency " + serviceId + " -> " + dependsOn + " already exists");
    }
}
