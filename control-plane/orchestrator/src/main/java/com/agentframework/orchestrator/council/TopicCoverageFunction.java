package com.agentframework.orchestrator.council;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Submodular coverage function based on topic diversity for council member selection.
 *
 * <p>Each council member profile covers a set of domain topics. The marginal gain of adding
 * a member is the number of <b>new</b> topics it brings that are not yet covered by the
 * selected set. This function is provably submodular (set cover is a canonical example).</p>
 *
 * <p>The default topic mapping covers the 8 standard council profiles:
 * managers (BE, FE, security, data) and specialists (database, auth, API, testing).</p>
 *
 * @see CoverageFunction
 * @see SubmodularSelector
 */
public class TopicCoverageFunction implements CoverageFunction<String> {

    private final Map<String, Set<String>> profileTopics;

    /**
     * @param profileTopics mapping from profile name to the set of topics it covers
     */
    public TopicCoverageFunction(Map<String, Set<String>> profileTopics) {
        this.profileTopics = Map.copyOf(profileTopics);
    }

    @Override
    public double marginalGain(Set<String> selected, String candidate) {
        Set<String> currentCoverage = coveredTopics(selected);
        Set<String> candidateTopics = profileTopics.getOrDefault(candidate, Set.of());
        return candidateTopics.stream()
                .filter(t -> !currentCoverage.contains(t))
                .count();
    }

    /**
     * Returns the union of all topics covered by the given profiles.
     */
    Set<String> coveredTopics(Set<String> selected) {
        return selected.stream()
                .flatMap(profile -> profileTopics.getOrDefault(profile, Set.of()).stream())
                .collect(Collectors.toSet());
    }

    /**
     * Returns the total number of unique topics across all profiles.
     */
    public int totalTopicCount() {
        return profileTopics.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet())
                .size();
    }

    /**
     * Returns the default topic mapping for the standard council profiles.
     */
    public static Map<String, Set<String>> defaultProfileTopics() {
        return Map.ofEntries(
            Map.entry("be-manager",          Set.of("backend", "api", "architecture", "java", "spring")),
            Map.entry("fe-manager",          Set.of("frontend", "ui", "react", "css", "accessibility")),
            Map.entry("mobile-manager",      Set.of("mobile", "ios", "android", "kotlin", "swiftui", "compose")),
            Map.entry("security-manager",    Set.of("security", "auth", "crypto", "owasp", "xss")),
            Map.entry("data-manager",        Set.of("database", "sql", "nosql", "migration", "schema")),
            Map.entry("database-specialist", Set.of("database", "sql", "indexing", "query-optimization")),
            Map.entry("auth-specialist",     Set.of("security", "auth", "oauth", "jwt", "keycloak")),
            Map.entry("api-specialist",      Set.of("api", "rest", "graphql", "openapi", "versioning")),
            Map.entry("testing-specialist",  Set.of("testing", "unit-test", "integration", "mocking", "coverage"))
        );
    }
}
