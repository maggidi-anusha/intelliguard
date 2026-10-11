package com.intelliguard.entity.enums;

// OPEN while any linked anomaly is open (or its quiet period is still running), RESOLVED after
// that, MERGED when the incident was absorbed into an older one (see Incident.mergedIntoId).
// INVESTIGATING is reserved for the Phase 6 incident workspace and is not set automatically.
public enum IncidentStatus {
    OPEN,
    INVESTIGATING,
    RESOLVED,
    MERGED
}
