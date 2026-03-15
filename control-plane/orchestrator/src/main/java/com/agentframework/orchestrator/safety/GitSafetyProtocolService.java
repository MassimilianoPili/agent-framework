package com.agentframework.orchestrator.safety;

import com.agentframework.orchestrator.safety.CommandClassifier.ClassificationResult;
import com.agentframework.orchestrator.safety.CommandClassifier.RiskLevel;
import com.agentframework.orchestrator.safety.SequenceAnalyzer.SequenceRisk;
import com.agentframework.orchestrator.safety.BranchProtectionPolicy.PolicyVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Git Safety Protocol: prevents destructive git operations by workers.
 *
 * <p>Orchestrates 3 levels of analysis:</p>
 * <ol>
 *   <li>{@link CommandClassifier}: individual command risk classification</li>
 *   <li>{@link SequenceAnalyzer}: cumulative sequence risk assessment</li>
 *   <li>{@link BranchProtectionPolicy}: protected branch enforcement</li>
 * </ol>
 *
 * <p>All evaluations are logged to {@code git_command_log} for audit.
 * Verdicts are ALLOW, WARN, or BLOCK.</p>
 *
 * <p>Research basis: STAC (Li 2025) demonstrated >90% ASR on SOTA coding
 * agents without sequence-level analysis.</p>
 */
@Service
@ConditionalOnProperty(prefix = "git-safety", name = "enabled", havingValue = "true", matchIfMissing = false)
public class GitSafetyProtocolService {

    private static final Logger log = LoggerFactory.getLogger(GitSafetyProtocolService.class);

    private final JdbcTemplate jdbcTemplate;
    private final BranchProtectionPolicy branchPolicy;

    /** Per-session sequence analyzers (keyed by worker session ID). */
    private final ConcurrentHashMap<String, SequenceAnalyzer> sessionAnalyzers = new ConcurrentHashMap<>();

    public GitSafetyProtocolService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.branchPolicy = new BranchProtectionPolicy();
    }

    /**
     * Evaluates a git command for safety.
     *
     * <p>Combines command classification, sequence analysis, and branch protection
     * into a single verdict.</p>
     *
     * @param command   the raw git command
     * @param sessionId worker session identifier (for sequence tracking)
     * @param currentBranch current branch name (for branch protection, nullable)
     * @return safety verdict
     */
    public SafetyVerdict evaluate(String command, String sessionId, @Nullable String currentBranch) {
        // Level 1: Command classification
        ClassificationResult classification = CommandClassifier.classify(command);

        // Not a git command — always allow
        if (!CommandClassifier.isGitCommand(command)) {
            return SafetyVerdict.ALLOW;
        }

        // Level 2: Sequence analysis
        SequenceAnalyzer analyzer = sessionAnalyzers.computeIfAbsent(sessionId, k -> new SequenceAnalyzer());
        SequenceRisk sequenceRisk = analyzer.analyze(command, classification);

        // Level 3: Branch protection
        String targetBranch = BranchProtectionPolicy.extractTargetBranch(command);
        if (targetBranch == null) targetBranch = currentBranch;
        PolicyVerdict branchVerdict = branchPolicy.evaluate(command, targetBranch);

        // Compose final verdict
        SafetyVerdict verdict = composeVerdict(command, classification, sequenceRisk, branchVerdict);

        // Audit log
        logCommand(sessionId, command, classification.level(), verdict);

        return verdict;
    }

    /**
     * Returns the current session's command history for debugging.
     */
    public Map<String, Object> sessionStatus(String sessionId) {
        SequenceAnalyzer analyzer = sessionAnalyzers.get(sessionId);
        if (analyzer == null) {
            return Map.of("session", sessionId, "commands", 0);
        }
        return Map.of(
                "session", sessionId,
                "commands", analyzer.recentHistory().size(),
                "history", analyzer.recentHistory().stream()
                        .map(e -> Map.of("command", e.command(),
                                "risk", e.classification().level().name(),
                                "reason", e.classification().reason()))
                        .toList());
    }

    /**
     * Clears session state (call on worker session end).
     */
    public void clearSession(String sessionId) {
        sessionAnalyzers.remove(sessionId);
    }

    // --- Private helpers ---

    private SafetyVerdict composeVerdict(String command, ClassificationResult classification,
                                          SequenceRisk sequenceRisk, PolicyVerdict branchVerdict) {
        // Branch policy violation → always BLOCK
        if (!branchVerdict.allowed()) {
            log.warn("Git safety BLOCK (branch policy): {} — {}", command, branchVerdict.reason());
            return new SafetyVerdict(Verdict.BLOCK, branchVerdict.reason(), classification.level());
        }

        // Sequence analysis: dangerous sequence → BLOCK
        if (sequenceRisk.level() == RiskLevel.DANGEROUS) {
            log.warn("Git safety BLOCK (sequence): {} — {}", command, sequenceRisk.reason());
            return new SafetyVerdict(Verdict.BLOCK, sequenceRisk.reason(), classification.level());
        }

        // Individual command: dangerous → BLOCK
        if (classification.level() == RiskLevel.DANGEROUS) {
            log.warn("Git safety BLOCK (command): {} — {}", command, classification.reason());
            return new SafetyVerdict(Verdict.BLOCK, classification.reason(), classification.level());
        }

        // Moderate commands → WARN (allow but log)
        if (classification.level() == RiskLevel.MODERATE) {
            log.debug("Git safety WARN: {} — {}", command, classification.reason());
            return new SafetyVerdict(Verdict.WARN, classification.reason(), classification.level());
        }

        return SafetyVerdict.ALLOW;
    }

    private void logCommand(String sessionId, String command, RiskLevel riskLevel,
                             SafetyVerdict verdict) {
        try {
            jdbcTemplate.update("""
                INSERT INTO git_command_log (id, session_id, raw_command, risk_level, verdict, reason)
                VALUES (?::uuid, ?, ?, ?, ?, ?)
                """,
                    UUID.randomUUID().toString(), sessionId, command,
                    riskLevel.name(), verdict.verdict().name(), verdict.reason());
        } catch (Exception e) {
            log.debug("Failed to log git command: {}", e.getMessage());
        }
    }

    // --- Public types ---

    public enum Verdict { ALLOW, WARN, BLOCK }

    /**
     * Safety evaluation verdict.
     *
     * @param verdict   ALLOW, WARN, or BLOCK
     * @param reason    human-readable explanation
     * @param riskLevel underlying command risk level
     */
    public record SafetyVerdict(Verdict verdict, String reason, RiskLevel riskLevel) {
        public static final SafetyVerdict ALLOW = new SafetyVerdict(Verdict.ALLOW, null, RiskLevel.SAFE);

        public boolean blocked() { return verdict == Verdict.BLOCK; }
        public boolean allowed() { return verdict != Verdict.BLOCK; }
    }
}
