package com.agentframework.orchestrator.council;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads council-related prompt files from the classpath.
 *
 * <p>Prompts are cached in memory on first load (immutable at runtime).
 * All files are expected under {@code src/main/resources/prompts/council/}.</p>
 *
 * <h3>File layout</h3>
 * <pre>
 * prompts/council/
 * ├── council-selector.agent.md    — member selection prompt
 * ├── council-manager.agent.md     — synthesis / facilitation prompt
 * ├── managers/
 * │   ├── be-manager.agent.md
 * │   ├── fe-manager.agent.md
 * │   ├── security-manager.agent.md
 * │   └── data-manager.agent.md
 * └── specialists/
 *     ├── database-specialist.agent.md
 *     ├── auth-specialist.agent.md
 *     ├── api-specialist.agent.md
 *     └── testing-specialist.agent.md
 * </pre>
 */
@Component
public class CouncilPromptLoader {

    private static final Logger log = LoggerFactory.getLogger(CouncilPromptLoader.class);
    private static final String BASE = "prompts/council/";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** Loads the council-selector system prompt. */
    public String loadSelectorPrompt() {
        return load(BASE + "council-selector.agent.md");
    }

    /** Loads the COUNCIL_MANAGER (facilitator/synthesizer) system prompt. */
    public String loadManagerPrompt() {
        return load(BASE + "council-manager.agent.md");
    }

    /**
     * Loads the system prompt for a specific member profile.
     *
     * @param profile member profile name (e.g. "be-manager", "database-specialist")
     * @return system prompt text
     * @throws RuntimeException if the prompt file is not found
     */
    /** Loads the Quadratic Voting suffix to append to member prompts when QV is enabled (#49). */
    public String loadQvSuffix() {
        return load(BASE + "member-qv.prompt.md");
    }

    public String loadMemberPrompt(String profile) {
        // Determine sub-directory from profile naming convention:
        // profiles ending in "-manager" → managers/, others → specialists/
        String subDir = profile.endsWith("-manager") ? "managers/" : "specialists/";
        return load(BASE + subDir + profile + ".agent.md");
    }

    private String load(String classpathPath) {
        return cache.computeIfAbsent(classpathPath, path -> {
            try {
                ClassPathResource resource = new ClassPathResource(path);
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                log.debug("Loaded council prompt: {}", path);
                return content;
            } catch (IOException e) {
                throw new RuntimeException("Cannot load council prompt: " + path
                    + ". Ensure the file exists at src/main/resources/" + path, e);
            }
        });
    }
}
