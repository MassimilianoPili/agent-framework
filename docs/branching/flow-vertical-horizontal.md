# Branching Strategy: Vertical and Horizontal Flow

## Branch Hierarchy

```
main (production)
  |
  +-- develop (integration)
  |     |
  |     +-- test (QA)
  |     |     |
  |     |     +-- collaudo (UAT / pre-prod)
  |     |
  |     +-- feature/BE-001-user-api        (vertical: BE worker)
  |     +-- feature/FE-001-user-dashboard   (vertical: FE worker)
  |     +-- feature/CT-001-user-spec        (vertical: contract worker)
  |
  +-- hotfix/fix-auth-bug                   (from main, merges to main + develop)
```

## Vertical Flow (Feature Development)

Each agent task creates a **vertical feature branch** from `develop`:

```
develop ----+------------------------------> develop
            |                                   ^
            +-- feature/BE-001-user-api --------+  (PR)
            +-- feature/FE-001-user-dashboard --+  (PR)
            +-- feature/CT-001-user-spec -------+  (PR)
```

- Workers create branches named `feature/{taskKey}-{description}`
- Each branch contains changes from a single worker
- PRs are opened against `develop`
- Review worker runs quality gates on each PR
- Auto-merge when all gates pass (for develop only)

## Horizontal Flow (Environment Cascade)

After PRs merge to `develop`, changes cascade through environments:

```
main --> develop --> test --> collaudo
  ^                              |
  +---------- release -----------+
```

- `develop` -> `test`: Automatic cascade (`.github/workflows/branch-cascade.yml`)
- `test` -> `collaudo`: Manual trigger after QA approval
- `collaudo` -> `main`: Release PR with human approval required

## Contract Lock

When `contracts/` files change on `develop`, the cascade pauses:
1. `breaking-check.sh` runs against the previous version
2. If breaking changes detected, cascade is blocked
3. Human review required before proceeding

See `scripts/git/rebase-with-contract-lock.sh` for the enforcement mechanism.

## Naming Conventions

| Pattern | Example | Purpose |
|---------|---------|---------|
| `feature/{taskKey}-{desc}` | `feature/BE-001-user-api` | Worker feature branch |
| `hotfix/{desc}` | `hotfix/fix-auth-timeout` | Production hotfix |
| `release/{version}` | `release/1.2.0` | Release candidate |
