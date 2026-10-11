package com.intelliguard.service;

import com.intelliguard.config.LegacyDependencyBackfill;
import com.intelliguard.dto.ServiceDependencyRequest;
import com.intelliguard.entity.Service;
import com.intelliguard.entity.ServiceDependency;
import com.intelliguard.entity.enums.Criticality;
import com.intelliguard.entity.enums.ServiceType;
import com.intelliguard.repository.ServiceDependencyRepository;
import com.intelliguard.repository.ServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Edge validation (404 unknown service, 400 self-loop, 409 duplicate, 400 cycle) and the
// idempotent startup copy of the legacy services.depends_on_service_id values.
class ServiceDependencyServiceTest {

    private final List<ServiceDependency> rows = new ArrayList<>();
    private final List<Service> services = new ArrayList<>();
    private final ServiceDependencyRepository dependencies = mock(ServiceDependencyRepository.class);
    private final ServiceRepository serviceRepository = mock(ServiceRepository.class);
    private final ServiceDependencyService service = new ServiceDependencyService(dependencies, serviceRepository);

    @BeforeEach
    void setUp() {
        // The seed topology: ids 1-8, with the four legacy single dependencies.
        String[] names = {"user-db", "order-db", "inventory-db", "auth-service", "inventory-service", "order-service",
                "api-gateway", "frontend-web"};
        Long[] legacy = {null, null, null, 1L, 3L, 2L, null, 7L};
        for (int i = 0; i < names.length; i++) {
            services.add(Service.builder().id(i + 1L).name(names[i]).type(ServiceType.MICROSERVICE)
                    .criticality(Criticality.HIGH).dependsOnServiceId(legacy[i]).build());
        }
        when(serviceRepository.findAll()).thenReturn(services);
        when(serviceRepository.existsById(anyLong())).thenAnswer(inv -> services.stream()
                .anyMatch(s -> s.getId().equals(inv.getArgument(0))));
        when(dependencies.findAll()).thenReturn(rows);
        when(dependencies.existsByServiceIdAndDependsOnServiceId(anyLong(), anyLong())).thenAnswer(inv -> rows.stream()
                .anyMatch(d -> d.getServiceId().equals(inv.getArgument(0)) && d.getDependsOnServiceId().equals(inv.getArgument(1))));
        when(dependencies.saveAndFlush(any())).thenAnswer(inv -> {
            ServiceDependency d = inv.getArgument(0);
            d.setId(rows.size() + 1L);
            rows.add(d);
            return d;
        });
    }

    @Test
    void aValidEdgeIsStored() {
        ServiceDependency saved = service.create(new ServiceDependencyRequest(7L, 6L));
        assertThat(saved.getServiceId()).isEqualTo(7L);
        assertThat(saved.getDependsOnServiceId()).isEqualTo(6L);
        assertThat(rows).hasSize(1);
    }

    @Test
    void selfLoopIsRejected() {
        assertStatus(() -> service.create(new ServiceDependencyRequest(6L, 6L)), HttpStatus.BAD_REQUEST);
        assertThat(rows).isEmpty();
    }

    @Test
    void unknownServiceIdsAreNotFound() {
        assertStatus(() -> service.create(new ServiceDependencyRequest(99L, 6L)), HttpStatus.NOT_FOUND);
        assertStatus(() -> service.create(new ServiceDependencyRequest(6L, 99L)), HttpStatus.NOT_FOUND);
        assertThat(rows).isEmpty();
    }

    @Test
    void duplicateIsAConflict() {
        service.create(new ServiceDependencyRequest(6L, 2L));
        assertStatus(() -> service.create(new ServiceDependencyRequest(6L, 2L)), HttpStatus.CONFLICT);
        assertThat(rows).hasSize(1);
    }

    @Test
    void edgesThatWouldCloseACycleAreRejected() {
        service.create(new ServiceDependencyRequest(8L, 7L));
        service.create(new ServiceDependencyRequest(7L, 6L));
        service.create(new ServiceDependencyRequest(6L, 2L));

        assertStatus(() -> service.create(new ServiceDependencyRequest(7L, 8L)), HttpStatus.BAD_REQUEST); // direct
        assertStatus(() -> service.create(new ServiceDependencyRequest(2L, 8L)), HttpStatus.BAD_REQUEST); // 2->8->7->6->2
        assertThat(rows).hasSize(3);
        service.create(new ServiceDependencyRequest(8L, 2L)); // a shortcut, not a cycle
        assertThat(rows).hasSize(4);
    }

    @Test
    void deletingAMissingEdgeIsNotFound() {
        when(dependencies.existsById(5L)).thenReturn(false);
        assertStatus(() -> service.delete(5L), HttpStatus.NOT_FOUND);
    }

    @Test
    void legacyCopyIsIdempotentAndSkipsInvalidValues() {
        services.get(0).setDependsOnServiceId(1L);   // self-loop: skipped
        services.get(1).setDependsOnServiceId(42L);  // unknown service: skipped

        assertThat(service.copyLegacyDependencies()).isEqualTo(4);
        assertThat(rows).extracting(d -> d.getServiceId() + "->" + d.getDependsOnServiceId())
                .containsExactlyInAnyOrder("4->1", "5->3", "6->2", "8->7");

        assertThat(service.copyLegacyDependencies()).isZero();
        new LegacyDependencyBackfill(service).run(null); // the startup runner: same copy, never throws
        assertThat(rows).hasSize(4);
    }

    private static void assertStatus(Runnable call, HttpStatus status) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(status));
    }
}
