package com.intelliguard.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

// One edge of the service dependency graph: serviceId calls / depends on dependsOnServiceId.
// Replaces the single Service.dependsOnServiceId (kept for backward compatibility and copied
// in here at startup), so a service can depend on several others. Kept acyclic by
// ServiceDependencyService.
@Entity
@Table(name = "service_dependencies",
        uniqueConstraints = @UniqueConstraint(name = "uk_service_dependencies_pair",
                columnNames = {"service_id", "depends_on_service_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_id", nullable = false)
    private Long serviceId;

    @Column(name = "depends_on_service_id", nullable = false)
    private Long dependsOnServiceId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
