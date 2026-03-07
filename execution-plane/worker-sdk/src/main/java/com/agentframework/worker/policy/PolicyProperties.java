package com.agentframework.worker.policy;

import com.agentframework.common.policy.ToolNames;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for the policy enforcement layer.
 *
 * <p>Bound from {@code agent.worker.policy.*} in each worker's application.yml.
 * Generated workers receive these values from their agent manifest YAML
 * via the agent-compiler-maven-plugin.</p>
 *
 * <p>Example configuration:</p>
 * <pre>{@code
 * agent.worker.policy:
 *   enabled: true
 *   worker-profile: be-java
 *   owns-paths:
 *     - backend/
 *   write-tool-names:
 *     - fs_write
 *   audit:
 *     enabled: true
 *     include-input: false
 *     max-input-length: 200
 * }</pre>
 */
@ConfigurationProperties(prefix = "agent.worker.policy")
public class PolicyProperties {

    private boolean enabled = true;

    /** Worker profile identity (e.g. be-java, fe-react, review). */
    private String workerProfile;

    /** Path prefixes this worker is allowed to write to. Empty = no restriction. */
    private List<String> ownsPaths = new ArrayList<>();

    /** Tool names considered "write" operations for ownership checks. */
    private List<String> writeToolNames = new ArrayList<>(ToolNames.WRITE_TOOLS);

    private AuditConfig audit = new AuditConfig();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getWorkerProfile() { return workerProfile; }
    public void setWorkerProfile(String workerProfile) { this.workerProfile = workerProfile; }

    public List<String> getOwnsPaths() { return ownsPaths; }
    public void setOwnsPaths(List<String> ownsPaths) { this.ownsPaths = ownsPaths; }

    public List<String> getWriteToolNames() { return writeToolNames; }
    public void setWriteToolNames(List<String> writeToolNames) { this.writeToolNames = writeToolNames; }

    public AuditConfig getAudit() { return audit; }
    public void setAudit(AuditConfig audit) { this.audit = audit; }

    public static class AuditConfig {

        private boolean enabled = true;

        /** Whether to include (truncated) tool input in audit logs. */
        private boolean includeInput = false;

        /** Maximum length of tool input to include in audit logs. */
        private int maxInputLength = 200;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isIncludeInput() { return includeInput; }
        public void setIncludeInput(boolean includeInput) { this.includeInput = includeInput; }

        public int getMaxInputLength() { return maxInputLength; }
        public void setMaxInputLength(int maxInputLength) { this.maxInputLength = maxInputLength; }
    }
}
