# Fase 4 — Advanced Mechanisms (#45-#49)

> Merkle DAG, commit-reveal, CAS completion, quadratic voting. Sessione S21.

---

## Sessione 20 — (inclusa in S8-S12)

> Le implementazioni Fase 11 (#87-#96) erano originariamente pianificate come sessione separata,
> ma sono state completate all'interno della maratona S8-S12 (commit `6ea8d72`, `95b42b7` del 2026-03-08).
> Vedi `07-fase-11-12-research.md` per dettagli.

---

## Sessione 21 — Merkle DAG + Commit-Reveal + CAS + Quadratic Voting (2026-03-10)

### Items completati

- **#45 Merkle DAG**: `DagHashService` con Kahn's topological sort, `dagHash` per item + `merkleRoot` per piano. V32 migration.
- **#46 Commit-Reveal**: `CouncilCommitment` scheme con nonce-based SHA-256 verification per council voting. V33 migration.
- **#48 CAS Completion**: `ArtifactController` REST API (GET artifact/metadata/dedup), `promptHash` traceability. V31 migration.
- **#49 Quadratic Voting**: `QuadraticVotingService` con ELO-based voice credits, budget validation, weighted synthesis per council decisions.

### File chiave

- `DagHashService.java` + V32 — #45
- `CouncilCommitment.java` + V33 — #46
- `ArtifactController.java` + V31 — #48
- `QuadraticVotingService.java` — #49

### Test

1074 test totali, 0 fallimenti.

**Commit**: `a067fd7`
