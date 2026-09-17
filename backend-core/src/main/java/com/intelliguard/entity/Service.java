package com.intelliguard.entity;

import com.intelliguard.entity.enums.Criticality;
import com.intelliguard.entity.enums.ServiceType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "services")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Service {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ServiceType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Criticality criticality;

    private String hostname;

    // Plain FK id, not a mapped relation - Phase 5 sets this manually to build the
    // dependency graph; no cascade/lazy-loading behavior is needed for that.
    private Long dependsOnServiceId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
