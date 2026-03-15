package com.agentframework.orchestrator.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Heuristic classifier that maps task title+description to a {@link CognitiveTaskType}.
 *
 * <p>Uses keyword pattern matching (not ML) because:</p>
 * <ol>
 *   <li>Runs on every dispatch — must be sub-millisecond</li>
 *   <li>Four-class coarse granularity is well-served by patterns</li>
 *   <li>No training data required — works from day one</li>
 * </ol>
 *
 * <p>Classification priority: REASONING &gt; VERIFICATION &gt; EXPLORATION &gt; STYLE (default).
 * First match wins within a priority tier. If no keywords match, defaults to REASONING
 * (conservative: assume high-risk until proven otherwise).</p>
 */
@Service
public class TaskTypeClassifier {

    private static final Logger log = LoggerFactory.getLogger(TaskTypeClassifier.class);

    private static final Map<CognitiveTaskType, Set<Pattern>> KEYWORD_PATTERNS = Map.of(
            CognitiveTaskType.REASONING, Set.of(
                    ci("algorithm"), ci("business.?logic"), ci("security"),
                    ci("auth"), ci("encrypt"), ci("data.?model"),
                    ci("migration"), ci("transaction"), ci("concurren"),
                    ci("state.?machine"), ci("validat"), ci("permission"),
                    ci("access.?control"), ci("schema"), ci("api.?design")),

            CognitiveTaskType.VERIFICATION, Set.of(
                    ci("test"), ci("verif"), ci("review"),
                    ci("ci/?cd"), ci("lint"), ci("assert"),
                    ci("coverage"), ci("quality.?gate"), ci("audit"),
                    ci("benchmark"), ci("regression")),

            CognitiveTaskType.EXPLORATION, Set.of(
                    ci("research"), ci("poc"), ci("prototype"),
                    ci("spike"), ci("experiment"), ci("investigate"),
                    ci("explore"), ci("feasibility"), ci("evaluate"),
                    ci("compare.?option")),

            CognitiveTaskType.STYLE, Set.of(
                    ci("format"), ci("renam"), ci("cosmetic"),
                    ci("indent"), ci("whitespace"), ci("comment"),
                    ci("javadoc"), ci("docstring"), ci("typo"),
                    ci("refactor.?nam"), ci("code.?style"))
    );

    /**
     * Classifies a task based on its title and description.
     *
     * @param title       task title (required)
     * @param description task description (nullable)
     * @return classified cognitive type; defaults to REASONING if ambiguous
     */
    public CognitiveTaskType classify(String title, String description) {
        String text = normalize(title, description);

        // Priority order: REASONING > VERIFICATION > EXPLORATION > STYLE
        CognitiveTaskType[] priority = {
                CognitiveTaskType.REASONING,
                CognitiveTaskType.VERIFICATION,
                CognitiveTaskType.EXPLORATION,
                CognitiveTaskType.STYLE
        };

        for (CognitiveTaskType type : priority) {
            Set<Pattern> patterns = KEYWORD_PATTERNS.get(type);
            if (patterns != null) {
                for (Pattern p : patterns) {
                    if (p.matcher(text).find()) {
                        log.debug("Task classified as {} (matched: '{}') — '{}'",
                                  type, p.pattern(), truncate(title, 60));
                        return type;
                    }
                }
            }
        }

        // Default: conservative — assume REASONING (highest review requirements)
        log.debug("Task classified as REASONING (default, no keyword match) — '{}'",
                  truncate(title, 60));
        return CognitiveTaskType.REASONING;
    }

    private static String normalize(String title, String description) {
        String t = title != null ? title : "";
        String d = description != null ? description : "";
        return (t + " " + d).toLowerCase();
    }

    private static Pattern ci(String keyword) {
        return Pattern.compile(keyword, Pattern.CASE_INSENSITIVE);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
