package com.intelliguard.entity.enums;

// OPEN while any linked anomaly is open (or its quiet period is still running), RESOLVED after
// that, MERGED when the incident was absorbed into an older one (see Incident.mergedIntoId).
// INVESTIGATING = OPEN and acknowledged by a USER/ADMIN (POST /api/incidents/{id}/acknowledge);
// the correlation job treats it like OPEN and still resolves it after the quiet period.
public enum IncidentStatus {
    OPEN,
    INVESTIGATING,
    RESOLVED,
    MERGED
}
