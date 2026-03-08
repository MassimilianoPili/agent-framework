package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Matches task capability requirements against worker capabilities using
 * Description Logic ALC (Baader et al., 1991 — Attributive Language with Complement).
 *
 * <p>ALC components:</p>
 * <ul>
 *   <li><b>TBox</b> (Terminological Box): the subsumption hierarchy of worker types,
 *       e.g., {@code be-java ⊑ backend ⊑ worker}.</li>
 *   <li><b>ABox</b> (Assertional Box): capability assertions per worker instance,
 *       e.g., {@code hasCapability(be-java, spring-boot)}.</li>
 *   <li><b>Subsumption</b>: C ⊑ D means every individual of C is also an instance of D.</li>
 *   <li><b>Satisfiability</b>: concept C is satisfiable if there exists at least one
 *       individual that is an instance of C.</li>
 * </ul>
 *
 * <p>Matching strategy: a worker satisfies a requirement if it either (1) directly asserts
 * the required capability in its ABox, or (2) its worker-type subsumes the required concept
 * in the TBox hierarchy via BFS up the subsumption chain.</p>
 */
@Service
@ConditionalOnProperty(prefix = "description-logic", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DescriptionLogicMatcher {

    private static final Logger log = LoggerFactory.getLogger(DescriptionLogicMatcher.class);

    /**
     * Built-in TBox: worker type → list of parent concepts (⊑ relation).
     * Represents the capability subsumption hierarchy of the framework's worker types.
     */
    private static final Map<String, List<String>> TBOX = buildTBox();

    private static Map<String, List<String>> buildTBox() {
        Map<String, List<String>> t = new LinkedHashMap<>();
        // Backend domain
        t.put("be-java",    List.of("backend", "jvm",       "worker"));
        t.put("be-kotlin",  List.of("backend", "jvm",       "worker"));
        t.put("be-dotnet",  List.of("backend", "jvm",       "worker"));
        t.put("be-python",  List.of("backend", "scripting", "worker"));
        t.put("be-node",    List.of("backend", "scripting", "worker"));
        t.put("be-go",      List.of("backend", "systems",   "worker"));
        t.put("be-rust",    List.of("backend", "systems",   "worker"));
        t.put("be-cpp",     List.of("backend", "systems",   "worker"));
        t.put("be-elixir",  List.of("backend", "functional","worker"));
        t.put("be-ocaml",   List.of("backend", "functional","worker"));
        // Frontend domain
        t.put("fe-react",     List.of("frontend", "javascript", "worker"));
        t.put("fe-angular",   List.of("frontend", "javascript", "worker"));
        t.put("fe-vue",       List.of("frontend", "javascript", "worker"));
        t.put("fe-svelte",    List.of("frontend", "javascript", "worker"));
        t.put("fe-nextjs",    List.of("frontend", "javascript", "ssr", "worker"));
        t.put("fe-vanillajs", List.of("frontend", "javascript", "worker"));
        // Database domain
        t.put("dba-postgres", List.of("database", "relational", "worker"));
        t.put("dba-mysql",    List.of("database", "relational", "worker"));
        t.put("dba-mssql",    List.of("database", "relational", "worker"));
        t.put("dba-oracle",   List.of("database", "relational", "worker"));
        t.put("dba-sqlite",   List.of("database", "relational", "worker"));
        t.put("dba-mongo",    List.of("database", "nosql",      "worker"));
        t.put("dba-redis",    List.of("database", "nosql",      "cache",   "worker"));
        t.put("dba-graphdb",  List.of("database", "graph",      "worker"));
        t.put("dba-vectordb", List.of("database", "vector",     "worker"));
        t.put("dba-cassandra",List.of("database", "nosql",      "worker"));
        // Infrastructure / Ops
        t.put("ops-k8s",      List.of("infrastructure", "orchestration", "worker"));
        // Middle-level concepts
        t.put("backend",        List.of("worker"));
        t.put("frontend",       List.of("worker"));
        t.put("database",       List.of("worker"));
        t.put("infrastructure", List.of("worker"));
        t.put("jvm",            List.of("worker"));
        t.put("scripting",      List.of("worker"));
        t.put("systems",        List.of("worker"));
        t.put("functional",     List.of("worker"));
        t.put("javascript",     List.of("worker"));
        t.put("relational",     List.of("database"));
        t.put("nosql",          List.of("database"));
        t.put("graph",          List.of("database"));
        t.put("vector",         List.of("database"));
        return Collections.unmodifiableMap(t);
    }

    /**
     * Matches the required capability against the provided worker ABox.
     *
     * @param requiredCapability the capability concept to satisfy (e.g., "jvm", "backend", "be-java")
     * @param workerCapabilities ABox: worker identifier → set of asserted capabilities
     * @return DL match report
     * @throws IllegalArgumentException if inputs are null/empty
     */
    public DLMatchReport match(String requiredCapability,
                                Map<String, Set<String>> workerCapabilities) {
        if (requiredCapability == null || requiredCapability.isBlank()) {
            throw new IllegalArgumentException("requiredCapability must not be blank");
        }
        if (workerCapabilities == null || workerCapabilities.isEmpty()) {
            throw new IllegalArgumentException("workerCapabilities must not be null or empty");
        }

        String req = requiredCapability.toLowerCase(Locale.ROOT);

        boolean satisfiable = isSatisfiable(req, workerCapabilities);

        List<String>              matchedWorkers   = new ArrayList<>();
        Map<String, List<String>> subsumptionPaths = new LinkedHashMap<>();

        for (Map.Entry<String, Set<String>> entry : workerCapabilities.entrySet()) {
            String      worker = entry.getKey();
            Set<String> caps   = entry.getValue();

            // Direct ABox assertion match
            if (caps.stream().anyMatch(c -> c.equalsIgnoreCase(req))) {
                matchedWorkers.add(worker);
                subsumptionPaths.put(worker, List.of(worker + " hasCapability " + req));
                continue;
            }

            // TBox subsumption match via BFS up the hierarchy
            List<String> path = findSubsumptionPath(worker.toLowerCase(Locale.ROOT), req);
            if (!path.isEmpty()) {
                matchedWorkers.add(worker);
                subsumptionPaths.put(worker, path);
            }
        }

        String explanation;
        if (matchedWorkers.isEmpty()) {
            explanation = "No worker satisfies '" + req + "'."
                    + (satisfiable ? "" : " Concept is not satisfiable in the TBox.");
        } else {
            explanation = "Matched " + matchedWorkers.size()
                    + " worker(s) for '" + req + "' via ABox + TBox subsumption.";
        }

        log.debug("DLMatch: required='{}' matched={} satisfiable={}", req, matchedWorkers, satisfiable);

        return new DLMatchReport(requiredCapability, matchedWorkers, subsumptionPaths,
                satisfiable, explanation);
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    private boolean isSatisfiable(String concept, Map<String, Set<String>> abox) {
        if ("worker".equals(concept)) return true;
        if (TBOX.containsKey(concept)) return true;
        return abox.values().stream()
                .anyMatch(caps -> caps.stream().anyMatch(c -> c.equalsIgnoreCase(concept)));
    }

    /**
     * BFS up the TBox hierarchy from {@code workerType} to {@code target}.
     * Returns a readable subsumption path (e.g., ["be-java ⊑ jvm", "jvm ⊑ worker"]),
     * or an empty list if no path exists.
     */
    private List<String> findSubsumptionPath(String workerType, String target) {
        Queue<List<String>> queue   = new ArrayDeque<>();
        Set<String>         visited = new HashSet<>();
        queue.add(List.of(workerType));

        while (!queue.isEmpty()) {
            List<String> path    = queue.poll();
            String       current = path.get(path.size() - 1);
            if (visited.contains(current)) continue;
            visited.add(current);

            if (current.equalsIgnoreCase(target)) {
                List<String> readable = new ArrayList<>();
                for (int i = 0; i + 1 < path.size(); i++) {
                    readable.add(path.get(i) + " \u2291 " + path.get(i + 1));
                }
                return readable;
            }

            List<String> parents = TBOX.get(current);
            if (parents != null) {
                for (String parent : parents) {
                    List<String> newPath = new ArrayList<>(path);
                    newPath.add(parent);
                    queue.add(newPath);
                }
            }
        }
        return Collections.emptyList();
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * Description Logic capability match report.
     *
     * @param requiredCapability the original requirement concept
     * @param matchedWorkers     workers satisfying the requirement (ABox or TBox)
     * @param subsumptionPaths   per-worker subsumption chain justifying each match
     * @param satisfiable        true if the concept has at least one instance in TBox/ABox
     * @param explanation        human-readable match summary
     */
    public record DLMatchReport(
            String requiredCapability,
            List<String> matchedWorkers,
            Map<String, List<String>> subsumptionPaths,
            boolean satisfiable,
            String explanation
    ) {}
}
