package com.intelliguard.controller;

import com.intelliguard.config.SecurityConfig;
import com.intelliguard.repository.AnomalyRepository;
import com.intelliguard.repository.RiskScoreRepository;
import com.intelliguard.repository.ServiceRepository;
import com.intelliguard.risk.RiskSnapshotStore;
import com.intelliguard.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// With the real SecurityConfig: the anomaly and risk APIs need a token (401 without), are
// readable by every role, are capped, and expose no write operation.
@WebMvcTest({AnomalyController.class, RiskController.class})
@Import(SecurityConfig.class)
class RiskAndAnomalyControllerTest {

    private static final List<String> ENDPOINTS = List.of("/api/anomalies", "/api/risk/current", "/api/risk/history");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AnomalyRepository anomalyRepository;
    @MockitoBean
    private RiskScoreRepository riskScoreRepository;
    @MockitoBean
    private ServiceRepository serviceRepository;
    @MockitoBean
    private RiskSnapshotStore snapshotStore;
    @MockitoBean
    private JwtUtil jwtUtil;

    @Test
    void withoutToken_everyEndpointIsUnauthorized() throws Exception {
        for (String url : ENDPOINTS) {
            mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void everyRoleCanRead() throws Exception {
        for (String role : List.of("VIEWER", "USER", "ADMIN")) {
            for (String url : ENDPOINTS) {
                mockMvc.perform(get(url).with(user("u").roles(role))).andExpect(status().isOk());
            }
        }
    }

    @Test
    void writesAreNotExposed() throws Exception {
        for (String url : ENDPOINTS) {
            mockMvc.perform(post(url).with(user("admin").roles("ADMIN"))).andExpect(status().isMethodNotAllowed());
        }
    }

    @Test
    void resultsAreCapped() throws Exception {
        mockMvc.perform(get("/api/anomalies").param("limit", "100000").with(user("u").roles("VIEWER"))).andExpect(status().isOk());
        mockMvc.perform(get("/api/risk/history").param("limit", "100000").with(user("u").roles("VIEWER"))).andExpect(status().isOk());

        ArgumentCaptor<Pageable> a = ArgumentCaptor.forClass(Pageable.class);
        verify(anomalyRepository).findFiltered(isNull(), isNull(), a.capture());
        assertThat(a.getValue().getPageSize()).isEqualTo(AnomalyController.MAX_RESULTS);
        ArgumentCaptor<Pageable> r = ArgumentCaptor.forClass(Pageable.class);
        verify(riskScoreRepository).findServiceHistory(isNull(), r.capture());
        assertThat(r.getValue().getPageSize()).isEqualTo(RiskController.MAX_HISTORY);
    }

    @Test
    void badStatusFilterIsRejected() throws Exception {
        mockMvc.perform(get("/api/anomalies").param("status", "MAYBE").with(user("u").roles("VIEWER")))
                .andExpect(status().isBadRequest());
        verify(anomalyRepository, org.mockito.Mockito.never()).findFiltered(any(), any(), any());
    }
}
