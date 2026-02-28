package com.agentframework.orchestrator.orchestration;

import com.agentframework.orchestrator.domain.WorkerType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves workerType + workerProfile to a concrete messaging topic and subscription.
 *
 * Resolution logic:
 * 1. If workerProfile is non-null → look up in the profiles map → return topic/subscription.
 * 2. If workerProfile is null → look up default profile for the workerType.
 *    - If default is non-null → resolve using step 1.
 *    - If default is null → type has no profiles, use WorkerType.topicName() directly.
 * 3. If workerProfile is unknown → throw UnknownWorkerProfileException.
 *
 * Configuration loaded from application.yml under prefix "worker-profiles".
 */
@Component
@ConfigurationProperties(prefix = "worker-profiles")
public class WorkerProfileRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkerProfileRegistry.class);

    private Map<String, ProfileEntry> profiles = Map.of();
    private Map<String, String> defaults = Map.of();

    public Map<String, ProfileEntry> getProfiles() {
        return profiles;
    }

    public void setProfiles(Map<String, ProfileEntry> profiles) {
        this.profiles = profiles;
    }

    public Map<String, String> getDefaults() {
        return defaults;
    }

    public void setDefaults(Map<String, String> defaults) {
        this.defaults = defaults;
    }

    /**
     * Validates registry consistency at startup. Fails fast if configuration is broken.
     */
    @PostConstruct
    void validate() {
        List<String> errors = new ArrayList<>();

        // 1. Every profile must have non-blank topic and subscription
        profiles.forEach((name, entry) -> {
            if (entry.topic() == null || entry.topic().isBlank()) {
                errors.add("Profile '" + name + "' has empty topic");
            }
            if (entry.subscription() == null || entry.subscription().isBlank()) {
                errors.add("Profile '" + name + "' has empty subscription");
            }
        });

        // 2. Default profiles must reference existing profile entries
        defaults.forEach((type, profileName) -> {
            if (profileName != null && !profiles.containsKey(profileName)) {
                errors.add("Default for " + type + " references unknown profile '" + profileName + "'");
            }
        });

        // 3. No duplicate subscriptions across profiles
        Map<String, List<String>> subscriptionToProfiles = profiles.entrySet().stream()
                .filter(e -> e.getValue().subscription() != null)
                .collect(Collectors.groupingBy(
                        e -> e.getValue().subscription(),
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
        subscriptionToProfiles.forEach((sub, profileNames) -> {
            if (profileNames.size() > 1) {
                errors.add("Duplicate subscription '" + sub + "' used by profiles: " + profileNames);
            }
        });

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "WorkerProfileRegistry validation failed:\n- " + String.join("\n- ", errors));
        }

        // 4. Warn for profiles without ownsPaths (non-blocking)
        profiles.forEach((name, entry) -> {
            if (entry.ownsPaths().isEmpty()) {
                log.warn("Profile '{}' has no ownsPaths — PathOwnershipSpec will reject all tasks", name);
            }
        });

        log.info("WorkerProfileRegistry validated: {} profiles, {} defaults",
                 profiles.size(), defaults.values().stream().filter(Objects::nonNull).count());
    }

    /**
     * Resolves the messaging topic for a given workerType + workerProfile combination.
     *
     * @param workerType the semantic worker type (BE, FE, AI_TASK, etc.)
     * @param workerProfile the concrete stack profile (be-java, be-go, etc.), may be null
     * @return the topic name to dispatch the task to
     * @throws UnknownWorkerProfileException if the profile is specified but not in the registry
     */
    public String resolveTopic(WorkerType workerType, String workerProfile) {
        String effectiveProfile = resolveEffectiveProfile(workerType, workerProfile);

        if (effectiveProfile == null) {
            // Type has no profiles — use direct topic from WorkerType
            return workerType.topicName();
        }

        ProfileEntry entry = profiles.get(effectiveProfile);
        if (entry == null) {
            throw new UnknownWorkerProfileException(
                    "Unknown worker profile '" + effectiveProfile + "' for type " + workerType);
        }

        return entry.topic();
    }

    /**
     * Resolves the subscription name for a given workerType + workerProfile combination.
     */
    public String resolveSubscription(WorkerType workerType, String workerProfile) {
        String effectiveProfile = resolveEffectiveProfile(workerType, workerProfile);

        if (effectiveProfile == null) {
            return workerType.topicName() + "-sub";
        }

        ProfileEntry entry = profiles.get(effectiveProfile);
        if (entry == null) {
            throw new UnknownWorkerProfileException(
                    "Unknown worker profile '" + effectiveProfile + "' for type " + workerType);
        }

        return entry.subscription();
    }

    /**
     * Returns the default profile for a WorkerType, or null if the type has no profiles.
     */
    public String resolveDefaultProfile(WorkerType workerType) {
        return defaults.get(workerType.name());
    }

    /**
     * Returns the ProfileEntry for a given profile name, or null if not found.
     * Used by the Specification pattern to validate capabilities before dispatch.
     */
    public ProfileEntry getProfileEntry(String profileName) {
        return profiles.get(profileName);
    }

    private String resolveEffectiveProfile(WorkerType workerType, String workerProfile) {
        if (workerProfile != null && !workerProfile.isBlank()) {
            return workerProfile;
        }
        return resolveDefaultProfile(workerType);
    }

    /**
     * A single profile entry in the registry (immutable).
     * Constructor-bound by Spring Boot from worker-profiles YAML.
     */
    public record ProfileEntry(String workerType, String topic, String subscription,
                                String displayName, java.util.List<String> mcpServers,
                                java.util.List<String> ownsPaths) {
        public ProfileEntry {
            mcpServers = mcpServers != null ? mcpServers : java.util.List.of();
            ownsPaths = ownsPaths != null ? ownsPaths : java.util.List.of();
        }

        /** Compatibility constructor for existing YAML without capability fields. */
        public ProfileEntry(String workerType, String topic, String subscription, String displayName) {
            this(workerType, topic, subscription, displayName, java.util.List.of(), java.util.List.of());
        }
    }

    /**
     * Thrown when a workerProfile is not found in the registry.
     * The orchestrator catches this and marks the PlanItem as FAILED.
     */
    public static class UnknownWorkerProfileException extends RuntimeException {
        public UnknownWorkerProfileException(String message) {
            super(message);
        }
    }
}
