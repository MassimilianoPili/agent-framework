# Agent Framework — Storico Implementazioni

Questo documento e' un indice. Il contenuto dettagliato e' in `documentazione/`.

---

## Indice per Fase

| Fase | File | Items | Sessioni | Contenuto |
|------|------|-------|----------|-----------|
| 1 — Fondazioni Core | [01-fondazioni-core.md](documentazione/01-fondazioni-core.md) | #1-#18 | S1-S9, S11 | ES, retry, compensation, RAG Pipeline, GP Engine, DPO, Ralph-Loop, SDK Scaffold |
| 2 — Orchestrator + Monitoring | [02-orchestrator-monitoring.md](documentazione/02-orchestrator-monitoring.md) | #19-#29 | S12-S14 | Leader election, monitoring dashboard, audit, provenance, worker lifecycle |
| 3 — Mathematical Foundations | [03-mathematical-foundations.md](documentazione/03-mathematical-foundations.md) | #30-#43 | S15-S19 | Hash chain, Ed25519, token economics, LTL, Shapley, Hungarian, policy lattice, PID |
| 4 — Advanced Mechanisms | [04-advanced-mechanisms.md](documentazione/04-advanced-mechanisms.md) | #45-#49 | S21 | Merkle DAG, commit-reveal, CAS, quadratic voting |
| 5 — Research Fase 8 | [05-fase-8-research.md](documentazione/05-fase-8-research.md) | #50-#61 | S8-research | Replicator dynamics, spectral graph, causal inference, optimal transport |
| 6 — Research Fase 9-10 | [06-fase-9-10-research.md](documentazione/06-fase-9-10-research.md) | #62-#86 | S10 | Game theory, control, rationality, neuroscience, physics-inspired |
| 7 — Research Fase 11-12 | [07-fase-11-12-research.md](documentazione/07-fase-11-12-research.md) | #87-#106 | S20, S22 | Formal methods, social choice, cybernetics, audit qualita' |
| 8 — Research Fase 13 | [08-fase-13-research.md](documentazione/08-fase-13-research.md) | #107-#116 | S23 | Context engineering, MCTS, amplification, Shapley, beliefs |

---

## Riepilogo File per Sessione

| Sessione | File nuovi | File mod | Test nuovi | Test totali |
|---|---|---|---|---|
| **S1** (Infra + Ingestion) | 25 | 5 | 53 | 261 |
| **S2** (Search + Graph + Java 21) | 15 | 10 | 47 | 308 |
| **S3** (RAG_MANAGER + Integ.) | 12 | 4 | 48 | 356 |
| **S4** (COMPENSATOR_MANAGER + Audit) | 2 | 1 | 8 | 364 |
| **S5** (Ralph-Loop + Roadmap #11-#18) | 8 | 6 | 8 | 372 |
| **S6** (GP Engine + Worker Selection #11) | 21 | 7 | 52 | 394 |
| **S7** (DPO con GP Residual #14) | 2 | 4 | 11 | 416 |
| **S8** (Serendipita' #12) | ~5 | ~3 | — | — |
| **S8-bugfix** (B1-B7, B9, B13-B16) | ~8 | ~12 | ~28 | — |
| **S8-workers** (26 workers + build.sh) | ~30 | ~2 | — | — |
| **S8-research** (Fasi 8a/8b/8d) | ~15 | ~5 | 22 | — |
| **S9** (Active Token Budget #15) | ~2 | ~3 | — | — |
| **S10** (Fase 9 + Fase 10) | 24 | 4 | ~50 | 632 |
| **S11** (Council #13 + Modello LLM #20) | 8 | 11 | 9 | 888 |
| **S12** (Leader Election #22 + Dashboard #28) | 7 | 8 | 9 | 734 |
| **S13** (Persistent Audit G5 + Provenance G2) | 5 | 4 | 4 | — |
| **S14** (Observability G1-G6 + Worker Lifecycle #29) | ~10 | ~8 | — | 2102 |
| **S15** (Context Cache #7 + Quality #35 + PID #37) | ~5 | ~5 | 25 | — |
| **S16** (Token Economics #33 + Shapley #40 + TM #24) | ~6 | ~5 | 54 | — |
| **S17** (Auto-Split #26 + Hash #30 + LTL #38 + Lattice #39) | ~6 | ~5 | — | 955 |
| **S18** (Hungarian #42 + SOC #56) | ~5 | ~3 | 32 | 987 |
| **S19** (Ed25519 #31 + Policy Hash #32) | ~4 | ~3 | — | — |
| **S21** (Merkle #45 + Commit-Reveal #46 + CAS #48 + QV #49) | ~6 | ~3 | — | 1074 |
| **S22** (Audit qualita' Fase 9-12) | 0 | ~5 | 8 | 1082 |
| **S23** (Fase 13 Research Applicativi #107-#116) | 17 | 5 | 89 | 1234 |

---

## Riepilogo sforzo complessivo

```
Fase 8  (#50-#61):   12 items, ~22.5g
Fase 9  (#62-#76):   15 items, ~33.0g
Fase 10 (#77-#86):   10 items, ~24.0g
Fase 11 (#87-#96):   10 items, ~22.5g
Fase 12 (#97-#106):  10 items, ~21.5g
Fase 13 (#107-#116): 10 items, ~24.0g
────────────────────────────────────────
Totale:              67 items, ~147.5g
```
