package com.agentframework.orchestrator.api;

import com.agentframework.common.crypto.Ed25519Signer;
import com.agentframework.orchestrator.crypto.WorkerKey;
import com.agentframework.orchestrator.crypto.WorkerKeyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for {@link WorkerKeyController} (#31 — key registration API).
 */
@WebMvcTest(WorkerKeyController.class)
@DisplayName("WorkerKeyController (#31)")
class WorkerKeyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkerKeyRepository repository;

    private final KeyPair kp = Ed25519Signer.generateKeyPair();
    private final String publicKeyB64 = Ed25519Signer.encodePublicKey(kp.getPublic());

    @Test
    @DisplayName("POST /api/v1/workers/keys — new key creates entry (201)")
    void registerKey_newKey_creates() throws Exception {
        when(repository.findByPublicKeyBase64AndDisabledFalse(anyString()))
                .thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String body = """
                {"workerType":"BE","workerProfile":"be-java","publicKeyBase64":"%s"}
                """.formatted(publicKeyB64);

        mockMvc.perform(post("/api/v1/workers/keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.workerType").value("BE"))
                .andExpect(jsonPath("$.publicKeyBase64").value(publicKeyB64))
                .andExpect(jsonPath("$.disabled").value(false));
    }

    @Test
    @DisplayName("POST /api/v1/workers/keys — existing key updates lastSeenAt (200)")
    void registerKey_existingKey_updatesLastSeen() throws Exception {
        WorkerKey existing = new WorkerKey("BE", "be-java", publicKeyB64);
        when(repository.findByPublicKeyBase64AndDisabledFalse(publicKeyB64))
                .thenReturn(Optional.of(existing));
        when(repository.save(any())).thenReturn(existing);

        String body = """
                {"workerType":"BE","workerProfile":"be-java","publicKeyBase64":"%s"}
                """.formatted(publicKeyB64);

        mockMvc.perform(post("/api/v1/workers/keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerType").value("BE"));

        verify(repository).save(existing);
    }

    @Test
    @DisplayName("DELETE /api/v1/workers/keys/{id} — soft-deletes key (204)")
    void disableKey_setsDisabledTrue() throws Exception {
        UUID id = UUID.randomUUID();
        WorkerKey key = new WorkerKey("BE", "be-java", publicKeyB64);
        when(repository.findById(id)).thenReturn(Optional.of(key));
        when(repository.save(any())).thenReturn(key);

        mockMvc.perform(delete("/api/v1/workers/keys/{id}", id))
                .andExpect(status().isNoContent());

        verify(repository).save(key);
    }

    @Test
    @DisplayName("GET /api/v1/workers/keys — lists active keys")
    void listKeys_returnsActiveKeys() throws Exception {
        WorkerKey key = new WorkerKey("BE", "be-java", publicKeyB64);
        when(repository.findByDisabledFalse()).thenReturn(List.of(key));

        mockMvc.perform(get("/api/v1/workers/keys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workerType").value("BE"))
                .andExpect(jsonPath("$[0].publicKeyBase64").value(publicKeyB64));
    }
}
