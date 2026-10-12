package com.intelliguard.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class IncidentNoteRequest {

    // Trimmed by IncidentWorkspaceService, then must be 1-2000 characters.
    @NotNull
    private String text;
}
