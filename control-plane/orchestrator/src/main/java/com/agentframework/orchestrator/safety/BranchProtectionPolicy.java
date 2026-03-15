package com.agentframework.orchestrator.safety;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Branch protection policy: prevents destructive operations on protected branches.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>No direct push to main/master/release/*</li>
 *   <li>No force-push to any protected branch</li>
 *   <li>Merge-only policy: changes must go through PR merge</li>
 *   <li>No branch deletion for protected branches</li>
 * </ul>
 */
public class BranchProtectionPolicy {

    /** Default protected branch patterns. */
    private static final List<Pattern> DEFAULT_PROTECTED = List.of(
            Pattern.compile("^main$"),
            Pattern.compile("^master$"),
            Pattern.compile("^release/.*"),
            Pattern.compile("^production$"),
            Pattern.compile("^staging$")
    );

    private final List<Pattern> protectedPatterns;

    public BranchProtectionPolicy() {
        this(DEFAULT_PROTECTED);
    }

    public BranchProtectionPolicy(List<Pattern> protectedPatterns) {
        this.protectedPatterns = protectedPatterns;
    }

    /**
     * Evaluates whether a git command targeting a branch is allowed.
     *
     * @param command    the git command
     * @param targetBranch the branch being targeted (extracted by caller)
     * @return policy verdict
     */
    public PolicyVerdict evaluate(String command, String targetBranch) {
        if (command == null || targetBranch == null) {
            return PolicyVerdict.ALLOW;
        }

        boolean isProtected = isProtectedBranch(targetBranch);
        if (!isProtected) {
            return PolicyVerdict.ALLOW;
        }

        String cmd = command.trim().toLowerCase();

        // Force push to protected branch
        if (cmd.contains("push") && (cmd.contains("--force") || cmd.contains("-f"))) {
            return new PolicyVerdict(false,
                    "force-push to protected branch '" + targetBranch + "' is forbidden");
        }

        // Direct push to protected branch (merge-only policy)
        if (cmd.contains("push") && !cmd.contains("--force")) {
            return new PolicyVerdict(false,
                    "direct push to protected branch '" + targetBranch + "' — use PR merge instead");
        }

        // Branch deletion
        if (cmd.contains("branch") && (cmd.contains("-d") || cmd.contains("-D") || cmd.contains("--delete"))) {
            return new PolicyVerdict(false,
                    "deletion of protected branch '" + targetBranch + "' is forbidden");
        }

        // Reset on protected branch
        if (cmd.contains("reset") && cmd.contains("--hard")) {
            return new PolicyVerdict(false,
                    "hard reset on protected branch '" + targetBranch + "' is forbidden");
        }

        return PolicyVerdict.ALLOW;
    }

    /**
     * Checks if a branch matches any protection pattern.
     */
    public boolean isProtectedBranch(String branchName) {
        if (branchName == null) return false;
        return protectedPatterns.stream().anyMatch(p -> p.matcher(branchName).matches());
    }

    /**
     * Extracts the target branch from a git push command.
     *
     * <p>Handles formats: {@code git push origin main}, {@code git push origin HEAD:main},
     * {@code git push -f origin release/1.0}</p>
     *
     * @param command the git push command
     * @return target branch name, or null if not determinable
     */
    public static String extractTargetBranch(String command) {
        if (command == null) return null;
        String[] parts = command.trim().split("\\s+");

        // Find push + remote + branch
        boolean foundPush = false;
        String remote = null;

        for (String part : parts) {
            if (part.equals("push")) { foundPush = true; continue; }
            if (!foundPush) continue;
            if (part.startsWith("-")) continue; // skip flags

            if (remote == null) {
                remote = part; // first non-flag after push = remote
            } else {
                // second non-flag = refspec
                if (part.contains(":")) {
                    return part.substring(part.indexOf(':') + 1); // HEAD:branch → branch
                }
                return part;
            }
        }

        return null;
    }

    /**
     * Policy verdict.
     *
     * @param allowed true if the operation is permitted
     * @param reason  explanation (null if allowed)
     */
    public record PolicyVerdict(boolean allowed, String reason) {
        public static final PolicyVerdict ALLOW = new PolicyVerdict(true, null);
    }
}
