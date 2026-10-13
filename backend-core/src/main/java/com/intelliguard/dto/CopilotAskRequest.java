package com.intelliguard.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CopilotAskRequest {

    // Trimmed by CopilotService, then must be 1-500 characters.
    private String question;
}
