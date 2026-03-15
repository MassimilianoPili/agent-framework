package com.agentframework.orchestrator.api;

import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for {@link ProfileController} — profile registry REST API.
 */
@WebMvcTest(ProfileController.class)
@DisplayName("ProfileController (#8)")
class ProfileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkerProfileRegistry profileRegistry;

    @BeforeEach
    void setUp() {
        Map<String, WorkerProfileRegistry.ProfileEntry> profiles = new LinkedHashMap<>();
        profiles.put("be-java", new WorkerProfileRegistry.ProfileEntry(
                "BE", "agent-tasks", "be-java-worker-sub", "Backend Java",
                List.of("git", "repo-fs"), List.of("backend/", "templates/be/")));
        profiles.put("be-go", new WorkerProfileRegistry.ProfileEntry(
                "BE", "agent-tasks", "be-go-worker-sub", "Backend Go",
                List.of("git"), List.of("backend/")));
        profiles.put("fe-react", new WorkerProfileRegistry.ProfileEntry(
                "FE", "agent-tasks", "fe-react-worker-sub", "Frontend React",
                List.of("git", "repo-fs"), List.of("frontend/")));

        when(profileRegistry.getProfiles()).thenReturn(profiles);
        when(profileRegistry.getProfileEntry("be-java")).thenReturn(profiles.get("be-java"));
        when(profileRegistry.getProfileEntry("be-go")).thenReturn(profiles.get("be-go"));
        when(profileRegistry.getProfileEntry("fe-react")).thenReturn(profiles.get("fe-react"));
        when(profileRegistry.getProfileEntry("unknown")).thenReturn(null);
        when(profileRegistry.resolveDefaultProfile(com.agentframework.orchestrator.domain.WorkerType.BE))
                .thenReturn("be-java");
        when(profileRegistry.resolveDefaultProfile(com.agentframework.orchestrator.domain.WorkerType.FE))
                .thenReturn("fe-react");
    }

    @Test
    @DisplayName("GET /api/v1/profiles — returns all profiles")
    void listProfiles_returnsAll() throws Exception {
        mockMvc.perform(get("/api/v1/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].name", is("be-java")))
                .andExpect(jsonPath("$[0].isDefault", is(true)))
                .andExpect(jsonPath("$[0].ownsPaths", containsInAnyOrder("backend/", "templates/be/")));
    }

    @Test
    @DisplayName("GET /api/v1/profiles?workerType=BE — filters by type")
    void listProfiles_filterByWorkerType() throws Exception {
        mockMvc.perform(get("/api/v1/profiles").param("workerType", "BE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].workerType", everyItem(is("BE"))));
    }

    @Test
    @DisplayName("GET /api/v1/profiles/be-java — returns single profile")
    void getProfile_found() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/be-java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("be-java")))
                .andExpect(jsonPath("$.workerType", is("BE")))
                .andExpect(jsonPath("$.displayName", is("Backend Java")))
                .andExpect(jsonPath("$.mcpServers", containsInAnyOrder("git", "repo-fs")))
                .andExpect(jsonPath("$.isDefault", is(true)));
    }

    @Test
    @DisplayName("GET /api/v1/profiles/unknown — returns 404")
    void getProfile_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/profiles/unknown"))
                .andExpect(status().isNotFound());
    }
}
