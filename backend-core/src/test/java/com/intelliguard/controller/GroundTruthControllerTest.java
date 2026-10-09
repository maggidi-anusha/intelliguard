package com.intelliguard.controller;

import com.intelliguard.config.SecurityConfig;
import com.intelliguard.entity.GroundTruthEvent;
import com.intelliguard.service.GroundTruthService;
import com.intelliguard.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Web-layer check with the real SecurityConfig: ground-truth labels are ADMIN-only in both
// directions, and malformed input is rejected with 400 before it reaches the service.
@WebMvcTest(GroundTruthController.class)
@Import(SecurityConfig.class)
class GroundTruthControllerTest {

    private static final String VALID_BODY = """
            {"runId": "live-demo-20261009T100000Z-s42", "seed": 42, "serviceId": 4,
             "signalType": "METRIC", "metricType": "ERROR_RATE", "scenarioType": "SPIKE",
             "difficulty": "EASY", "startTime": "2026-10-09T10:06:00.000000Z",
             "endTime": "2026-10-09T10:07:55.000000Z", "params": {"episode_id": "demo-2"}}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GroundTruthService groundTruthService;

    // Needed by JwtAuthenticationFilter; requests here authenticate via user(...) instead.
    @MockitoBean
    private JwtUtil jwtUtil;

    @Test
    void post_withoutToken_isUnauthorized() throws Exception {
        mockMvc.perform(post("/api/ground-truth").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        verify(groundTruthService, never()).record(any());
    }

    @Test
    void post_asUserOrViewer_isForbidden() throws Exception {
        for (String role : List.of("USER", "VIEWER")) {
            mockMvc.perform(post("/api/ground-truth").with(user("u").roles(role))
                            .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                    .andExpect(status().isForbidden());
        }
        verify(groundTruthService, never()).record(any());
    }

    @Test
    void get_asUserOrViewer_isForbidden() throws Exception {
        for (String role : List.of("USER", "VIEWER")) {
            mockMvc.perform(get("/api/ground-truth").with(user("u").roles(role)))
                    .andExpect(status().isForbidden());
        }
        verify(groundTruthService, never()).find(any(), any());
    }

    @Test
    void post_asAdminWithValidBody_isCreated() throws Exception {
        when(groundTruthService.record(any())).thenReturn(GroundTruthEvent.builder().id(1L).build());

        mockMvc.perform(post("/api/ground-truth").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void post_asAdminWithMissingRequiredFields_isBadRequest() throws Exception {
        mockMvc.perform(post("/api/ground-truth").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"runId\": \"r1\"}"))
                .andExpect(status().isBadRequest());
        verify(groundTruthService, never()).record(any());
    }

    @Test
    void post_asAdminWithUnknownEnumValue_isBadRequest() throws Exception {
        String body = VALID_BODY.replace("\"SPIKE\"", "\"EARTHQUAKE\"");
        mockMvc.perform(post("/api/ground-truth").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(groundTruthService, never()).record(any());
    }

    @Test
    void get_asAdminWithNonNumericServiceId_isBadRequest() throws Exception {
        mockMvc.perform(get("/api/ground-truth").param("serviceId", "abc").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_asAdmin_isOk() throws Exception {
        when(groundTruthService.find("r1", 4L)).thenReturn(List.of());

        mockMvc.perform(get("/api/ground-truth").param("runId", "r1").param("serviceId", "4")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }
}
