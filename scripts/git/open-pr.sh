#!/bin/bash
# ──────────────────────────────────────────────────────────────────────
# Open a pull request for the current agent branch.
#
# Usage: ./open-pr.sh [base-branch]
# Default base: develop
# Requires: gh CLI authenticated
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

BASE="${1:-develop}"
CURRENT=$(git branch --show-current)

if [[ ! "$CURRENT" == agent/* ]]; then
  echo "WARNING: Current branch '$CURRENT' does not follow agent/ convention."
fi

echo "Pushing branch '$CURRENT' and creating PR against '$BASE'..."
git push -u origin "$CURRENT"

gh pr create \
  --base "$BASE" \
  --head "$CURRENT" \
  --title "Agent: $(echo "$CURRENT" | sed 's|agent/||; s|/| - |g')" \
  --body "$(cat <<EOF
## Agent-generated PR

**Branch**: \`$CURRENT\`
**Base**: \`$BASE\`

### Changes
<!-- Auto-filled by agent -->
_See commits for details._

### Quality Checklist
- [ ] Tests pass
- [ ] No breaking contract changes
- [ ] No secrets committed
- [ ] Code follows project patterns
EOF
)"

echo "PR created."
