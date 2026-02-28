package com.agentframework.compiler.manifest;

import java.util.List;
import java.util.Map;

/**
 * POJO model for agent manifest YAML files.
 * Deserialized by SnakeYAML from {@code *.agent.yml} files.
 *
 * <p>Corresponds to {@code contracts/agent-schemas/AgentManifest.schema.json}.</p>
 */
public class AgentManifest {

    private String apiVersion;
    private String kind;
    private Metadata metadata;
    private Spec spec;

    // --- Nested classes ---

    public static class Metadata {
        private String name;
        private String displayName;
        private String description;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class Spec {
        private String workerType;
        private String workerProfile;
        private String topic;
        private String subscription;
        private Model model;
        private Prompts prompts;
        private Tools tools;
        private Ownership ownership;
        private Concurrency concurrency;
        private Retry retry;

        public String getWorkerType() { return workerType; }
        public void setWorkerType(String workerType) { this.workerType = workerType; }
        public String getWorkerProfile() { return workerProfile; }
        public void setWorkerProfile(String workerProfile) { this.workerProfile = workerProfile; }
        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }
        public String getSubscription() { return subscription; }
        public void setSubscription(String subscription) { this.subscription = subscription; }
        public Model getModel() { return model; }
        public void setModel(Model model) { this.model = model; }
        public Prompts getPrompts() { return prompts; }
        public void setPrompts(Prompts prompts) { this.prompts = prompts; }
        public Tools getTools() { return tools; }
        public void setTools(Tools tools) { this.tools = tools; }
        public Ownership getOwnership() { return ownership; }
        public void setOwnership(Ownership ownership) { this.ownership = ownership; }
        public Concurrency getConcurrency() { return concurrency; }
        public void setConcurrency(Concurrency concurrency) { this.concurrency = concurrency; }
        public Retry getRetry() { return retry != null ? retry : new Retry(); }
        public void setRetry(Retry retry) { this.retry = retry; }
        /** Max number of automatic context-retry loops before failing the task. Default: 1. */
        private int maxContextRetries = 1;
        public int getMaxContextRetries() { return maxContextRetries; }
        public void setMaxContextRetries(int maxContextRetries) { this.maxContextRetries = maxContextRetries; }
    }

    public static class Model {
        private String name = "claude-sonnet-4-6";
        private int maxTokens = 16384;
        private double temperature = 0.2;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
        public double getTemperature() { return temperature; }
        public void setTemperature(double temperature) { this.temperature = temperature; }
    }

    public static class Prompts {
        private String systemPromptFile;
        private List<String> skills;
        private String instructions;
        private String resultSchema;

        public String getSystemPromptFile() { return systemPromptFile; }
        public void setSystemPromptFile(String systemPromptFile) { this.systemPromptFile = systemPromptFile; }
        public List<String> getSkills() { return skills; }
        public void setSkills(List<String> skills) { this.skills = skills; }
        public String getInstructions() { return instructions; }
        public void setInstructions(String instructions) { this.instructions = instructions; }
        public String getResultSchema() { return resultSchema; }
        public void setResultSchema(String resultSchema) { this.resultSchema = resultSchema; }
    }

    public static class Tools {
        private List<String> dependencies;
        private List<String> allowlist;
        private List<String> mcpServers;

        public List<String> getDependencies() { return dependencies; }
        public void setDependencies(List<String> dependencies) { this.dependencies = dependencies; }
        public List<String> getAllowlist() { return allowlist; }
        public void setAllowlist(List<String> allowlist) { this.allowlist = allowlist; }
        public List<String> getMcpServers() { return mcpServers != null ? mcpServers : List.of(); }
        public void setMcpServers(List<String> mcpServers) { this.mcpServers = mcpServers; }
    }

    public static class Ownership {
        private List<String> ownsPaths;
        private List<String> readOnlyPaths;

        public List<String> getOwnsPaths() { return ownsPaths; }
        public void setOwnsPaths(List<String> ownsPaths) { this.ownsPaths = ownsPaths; }
        public List<String> getReadOnlyPaths() { return readOnlyPaths; }
        public void setReadOnlyPaths(List<String> readOnlyPaths) { this.readOnlyPaths = readOnlyPaths; }
    }

    public static class Concurrency {
        private int maxConcurrentCalls = 3;

        public int getMaxConcurrentCalls() { return maxConcurrentCalls; }
        public void setMaxConcurrentCalls(int maxConcurrentCalls) { this.maxConcurrentCalls = maxConcurrentCalls; }
    }

    public static class Retry {
        private int maxAttempts = 3;
        private long backoffMs = 5000;
        /** Piano transitions to PAUSED after this many failed attempts (before maxAttempts). */
        private int attemptsBeforePause = 2;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getBackoffMs() { return backoffMs; }
        public void setBackoffMs(long backoffMs) { this.backoffMs = backoffMs; }
        public int getAttemptsBeforePause() { return attemptsBeforePause; }
        public void setAttemptsBeforePause(int attemptsBeforePause) { this.attemptsBeforePause = attemptsBeforePause; }
    }

    // --- Root getters/setters ---

    public String getApiVersion() { return apiVersion; }
    public void setApiVersion(String apiVersion) { this.apiVersion = apiVersion; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Metadata getMetadata() { return metadata; }
    public void setMetadata(Metadata metadata) { this.metadata = metadata; }
    public Spec getSpec() { return spec; }
    public void setSpec(Spec spec) { this.spec = spec; }
}
