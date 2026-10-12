package com.intelliguard.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

// A free-text note on an incident (Phase 6A workspace). Written only through
// POST /api/incidents/{id}/notes by a USER or ADMIN; deleted together with its incident.
@Entity
@Table(name = "incident_notes", indexes = @Index(name = "idx_incident_notes_incident_id", columnList = "incident_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    // Username from the JWT of whoever wrote it.
    @Column(nullable = false)
    private String author;

    // Trimmed, 1-2000 characters.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
