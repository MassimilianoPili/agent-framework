package com.agentframework.orchestrator.api;

import com.agentframework.orchestrator.domain.WorkerType;
import com.agentframework.orchestrator.orchestration.WorkerProfileRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for inspecting registered worker profiles and their capabilities.
 *
 * <p>Provides read-only access to the {@link WorkerProfileRegistry} for
 * monitoring dashboards and diagnostic tools.</p>
 */
@RestController
@RequestMapping("/api/v1/profiles")
public class ProfileController {

    private final WorkerProfileRegistry profileRegistry;

    public ProfileController(WorkerProfileRegistry profileRegistry) {
        this.profileRegistry = profileRegistry;
    }

    /**
     * Lists all registered profiles, optionally filtered by worker type.
     *
     * @param workerType optional filter (e.g. "BE", "FE", "DBA")
     * @return list of profile DTOs
     */
    @GetMapping
    public List<ProfileDto> listProfiles(
            @RequestParam(required = false) String workerType) {

        Map<String, WorkerProfileRegistry.ProfileEntry> allProfiles = profileRegistry.getProfiles();

        return allProfiles.entrySet().stream()
                .filter(e -> workerType == null || workerType.isBlank()
                        || workerType.equalsIgnoreCase(e.getValue().getWorkerType()))
                .map(e -> toDto(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * Returns a single profile by name, or 404 if not found.
     */
    @GetMapping("/{profileName}")
    public ResponseEntity<ProfileDto> getProfile(@PathVariable String profileName) {
        WorkerProfileRegistry.ProfileEntry entry = profileRegistry.getProfileEntry(profileName);
        if (entry == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toDto(profileName, entry));
    }

    private ProfileDto toDto(String name, WorkerProfileRegistry.ProfileEntry entry) {
        String defaultForType = profileRegistry.resolveDefaultProfile(
                WorkerType.valueOf(entry.getWorkerType()));
        boolean isDefault = name.equals(defaultForType);

        return new ProfileDto(
                name,
                entry.getWorkerType(),
                entry.getDisplayName(),
                entry.getTopic(),
                entry.getSubscription(),
                entry.getMcpServers(),
                entry.getOwnsPaths(),
                isDefault);
    }

    record ProfileDto(
            String name,
            String workerType,
            String displayName,
            String topic,
            String subscription,
            List<String> mcpServers,
            List<String> ownsPaths,
            boolean isDefault
    ) {}
}
