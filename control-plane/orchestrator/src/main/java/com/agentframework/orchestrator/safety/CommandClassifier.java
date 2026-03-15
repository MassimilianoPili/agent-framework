package com.agentframework.orchestrator.safety;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Classifies git commands into 3 risk levels: SAFE, MODERATE, DANGEROUS.
 *
 * <p>Based on STAC threat model (Li 2025): agents without command-level
 * classification are vulnerable to prompt injection that escalates
 * from safe to destructive operations.</p>
 *
 * <ul>
 *   <li><b>SAFE</b>: read-only operations (status, log, diff, branch -l)</li>
 *   <li><b>MODERATE</b>: state-changing but reversible (add, commit, push, checkout)</li>
 *   <li><b>DANGEROUS</b>: destructive or hard-to-reverse (reset --hard, push --force, rebase, clean -f)</li>
 * </ul>
 */
public class CommandClassifier {

    public enum RiskLevel { SAFE, MODERATE, DANGEROUS }

    // --- SAFE: read-only git commands ---
    private static final Set<String> SAFE_SUBCOMMANDS = Set.of(
            "status", "log", "diff", "show", "blame", "shortlog",
            "describe", "tag -l", "branch -l", "branch --list",
            "remote -v", "config --list", "config --get",
            "rev-parse", "ls-files", "ls-tree", "cat-file",
            "reflog", "stash list", "worktree list"
    );

    // --- DANGEROUS: patterns that indicate destructive operations ---
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            Pattern.compile("\\breset\\s+--hard\\b"),
            Pattern.compile("\\bpush\\s+.*--force\\b"),
            Pattern.compile("\\bpush\\s+.*-f\\b"),
            Pattern.compile("\\bpush\\s+--force-with-lease\\b"),
            Pattern.compile("\\bclean\\s+.*-f\\b"),
            Pattern.compile("\\bclean\\s+.*-d\\b"),
            Pattern.compile("\\bbranch\\s+.*-D\\b"),
            Pattern.compile("\\bbranch\\s+.*--delete\\s+--force\\b"),
            Pattern.compile("\\brebase\\b"),
            Pattern.compile("\\bcheckout\\s+--\\s+\\."),            // checkout -- . (discard all)
            Pattern.compile("\\brestore\\s+--staged\\s+--worktree"), // discard both staged and working
            Pattern.compile("\\bfilter-branch\\b"),
            Pattern.compile("\\breflog\\s+delete\\b"),
            Pattern.compile("\\breflog\\s+expire\\b"),
            Pattern.compile("\\bgc\\s+--prune=now\\b"),
            Pattern.compile("\\bsubmodule\\s+deinit\\s+-f\\b")
    );

    // --- MODERATE: state-changing but generally recoverable ---
    private static final Set<String> MODERATE_SUBCOMMANDS = Set.of(
            "add", "commit", "push", "pull", "fetch", "merge",
            "checkout", "switch", "restore", "stash", "stash pop",
            "stash drop", "tag", "branch", "cherry-pick", "revert",
            "rm", "mv", "submodule", "worktree add"
    );

    private CommandClassifier() {}

    /**
     * Classifies a git command string by risk level.
     *
     * @param command raw command string (may include "git" prefix)
     * @return classification result with risk level and reason
     */
    public static ClassificationResult classify(String command) {
        if (command == null || command.isBlank()) {
            return new ClassificationResult(RiskLevel.SAFE, "empty command");
        }

        String normalized = command.trim().toLowerCase();

        // Strip leading "git " if present
        if (normalized.startsWith("git ")) {
            normalized = normalized.substring(4).trim();
        }

        // Not a git command → SAFE (we only classify git commands)
        if (!isGitCommand(command)) {
            return new ClassificationResult(RiskLevel.SAFE, "not a git command");
        }

        // Check DANGEROUS patterns first (highest priority)
        for (Pattern p : DANGEROUS_PATTERNS) {
            if (p.matcher(normalized).find()) {
                return new ClassificationResult(RiskLevel.DANGEROUS,
                        "matches dangerous pattern: " + p.pattern());
            }
        }

        // Check SAFE subcommands
        for (String safe : SAFE_SUBCOMMANDS) {
            if (normalized.startsWith(safe)) {
                return new ClassificationResult(RiskLevel.SAFE, "read-only: " + safe);
            }
        }

        // Check MODERATE subcommands
        String subcommand = normalized.split("\\s+")[0];
        if (MODERATE_SUBCOMMANDS.contains(subcommand)) {
            return new ClassificationResult(RiskLevel.MODERATE, "state-changing: " + subcommand);
        }

        // Unknown git subcommand → default MODERATE (precautionary)
        return new ClassificationResult(RiskLevel.MODERATE, "unknown git subcommand: " + subcommand);
    }

    /**
     * Checks if a raw command is a git command.
     */
    public static boolean isGitCommand(String command) {
        if (command == null) return false;
        String trimmed = command.trim().toLowerCase();
        return trimmed.startsWith("git ") || trimmed.equals("git");
    }

    /**
     * Classification result.
     *
     * @param level  risk level
     * @param reason human-readable classification reason
     */
    public record ClassificationResult(RiskLevel level, String reason) {}
}
