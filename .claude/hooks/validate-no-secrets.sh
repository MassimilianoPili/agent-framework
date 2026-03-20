#!/bin/bash
# ──────────────────────────────────────────────────────────────────────
# Dual-purpose secret scanner:
#   - PreToolUse (Edit|Write): scans file content BEFORE write
#   - Stop: scans all staged files at session end
#
# Detects the hook context from CLAUDE_HOOK_EVENT env var or from
# the presence of tool_input in stdin (PreToolUse) vs absence (Stop).
#
# Patterns: API keys, AWS AKIA, Azure connection strings, private keys,
# JWTs, GitHub tokens, generic high-entropy secrets.
#
# Exit codes:
#   0 — no secrets detected
#   2 — potential secrets found (blocks write in PreToolUse, warns in Stop)
# ──────────────────────────────────────────────────────────────────────
set -uo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(pwd)}"
ISSUES=()

# Secret patterns (POSIX extended regex)
SECRET_PATTERNS=(
    '(api[_-]?key|secret[_-]?key|access[_-]?token|private[_-]?key|password)\s*[:=]\s*["\x27]?[A-Za-z0-9+/=_-]{20,}'
    'AKIA[0-9A-Z]{16}'
    'Endpoint=sb://'
    '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'
    'ghp_[A-Za-z0-9]{36}'
    'ghs_[A-Za-z0-9]{36}'
    'sk-[A-Za-z0-9]{20,}'
    'eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.'
)

scan_content() {
    local file="$1"
    local content="$2"

    for pattern in "${SECRET_PATTERNS[@]}"; do
        local match
        match=$(echo "$content" | grep -EnP "$pattern" 2>/dev/null | head -3 || true)
        if [[ -n "$match" ]]; then
            ISSUES+=("$file: $(echo "$match" | head -1 | cut -c1-120)")
        fi
    done
}

scan_file() {
    local filepath="$1"
    if [[ -f "$filepath" ]]; then
        local content
        content=$(cat "$filepath" 2>/dev/null || true)
        scan_content "$filepath" "$content"
    fi
}

# --- Determine context ---
INPUT=$(cat)

# Check if this is a PreToolUse call (has tool_input with content/new_string)
TOOL_INPUT_FILE=$(echo "$INPUT" | jq -r '.tool_input.file_path // empty' 2>/dev/null)
TOOL_INPUT_CONTENT=$(echo "$INPUT" | jq -r '.tool_input.new_string // .tool_input.content // empty' 2>/dev/null)

if [[ -n "$TOOL_INPUT_CONTENT" ]]; then
    # --- PreToolUse mode: scan the content about to be written ---
    FILE_LABEL="${TOOL_INPUT_FILE:-<inline>}"
    scan_content "$FILE_LABEL" "$TOOL_INPUT_CONTENT"

    if [[ ${#ISSUES[@]} -gt 0 ]]; then
        echo "BLOCKED: Potential secrets detected in content for '$FILE_LABEL':" >&2
        for issue in "${ISSUES[@]}"; do
            echo "  - $issue" >&2
        done
        exit 2
    fi
    exit 0
fi

# --- Stop mode: scan all staged files ---
if command -v git &>/dev/null && git -C "$PROJECT_DIR" rev-parse --git-dir &>/dev/null; then
    STAGED_FILES=$(git -C "$PROJECT_DIR" diff --cached --name-only 2>/dev/null || true)

    if [[ -n "$STAGED_FILES" ]]; then
        # Check suspicious extensions
        SUSPICIOUS_EXTS=$(echo "$STAGED_FILES" | grep -Ei '\.(env|key|pem|p12|pfx|credentials|secret)$' || true)
        if [[ -n "$SUSPICIOUS_EXTS" ]]; then
            ISSUES+=("Staged files with suspicious extensions: $SUSPICIOUS_EXTS")
        fi

        # Scan content
        for file in $STAGED_FILES; do
            scan_file "$PROJECT_DIR/$file"
        done
    fi
fi

if [[ ${#ISSUES[@]} -gt 0 ]]; then
    echo "WARNING: Potential secrets detected in staged files:" >&2
    for issue in "${ISSUES[@]}"; do
        echo "  - $issue" >&2
    done
    echo "" >&2
    echo "Review staged changes before committing. Use 'git diff --cached' to inspect." >&2
    exit 2
fi

exit 0