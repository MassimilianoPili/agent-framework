# Fase 6 — Research Domains Fase 9-10 (#62-#86)

> Game Theory, Control Theory, Rationality, Neuroscience-inspired, Physics-inspired.
> Sessione S10.

---

## Sessione 10 — Research Domains Fase 9 completamento + Fase 10 (#77-#86) ✅ COMPLETATA

> **Stato**: completata il 2026-03-08. Fase 9 completata al 100% (13/15 → 15/15).
> Fase 10 (#77-#86) implementata interamente: 10 nuovi Analytics Services.

### S10-A. Completamento Fase 9 (3 item mancanti)

| # | Item | Servizio |
|---|------|---------|
| #76 | Superrationality (Hofstadter) | `SuperrationalityService` — cooperation gain tra worker type pairs |
| #72 | TDT/FDT Reflective Dispatch | `ReflectiveDispatchService` — politica timeless via argmax reward storico |

_(#63 MPC era gia' implementato come `MpcSchedulerService`)_

### S10-B. Fase 10 — 10 nuovi Analytics Services

| # | Item | Servizio | Algoritmo chiave |
|---|------|---------|-----------------|
| #79 | MDL (Rissanen 1978) | `MDLService` | L(DAG) + L(outcomes\|DAG), normalizzato per N |
| #82 | H∞ Robust Control (Zhou-Doyle) | `HInfinityRobustService` | worst-case = mean − k·std (NormalDist quantile) |
| #84 | Edge of Chaos (Langton) | `EdgeOfChaosService` | Lyapunov proxy: Var(diffs)/Var(rewards) |
| #77 | Active Inference / FEP (Friston) | `ActiveInferenceService` | F = −GP.mu + klWeight·GP.sigma² |
| #78 | Information Bottleneck (Tishby) | `InformationBottleneckService` | SVD via EJML, explained variance ratio |
| #81 | Spin Glass / SA (Kirkpatrick) | `SpinGlassDispatchService` | Simulated Annealing, T_i = T₀·rate^i |
| #83 | Byzantine Fault Tolerance (PBFT) | `ByzantineFaultToleranceService` | majority voting > 2/3, byzantine detection |
| #80 | Renormalization Group (Wilson) | `RenormalizationGroupService` | coupling flow: fine/medium/coarse block scales |
| #85 | Persistent Homology (Edelsbrunner) | `PersistentHomologyService` | Vietoris-Rips + Union-Find β₀ barcodes |
| #86 | Functorial Semantics (Mac Lane) | `FunctorialSemanticsService` | Functor F: item→gp_mu, η: η(item)=actual−gp_mu |

### S10-C. Infrastruttura

- `pom.xml`: aggiunta dipendenza `commons-math3:3.6.1` (NormalDistribution per H∞)
- `TaskOutcomeRepository`: aggiunto `findPlanWorkerRewardSummary()`, `findRewardTimeseriesByWorkerType()`, `findOutcomesWithEmbeddingByWorkerType()`
- `application.yml`: 12 nuovi blocchi config (superrationality, fdt, mdl, h-infinity, edge-of-chaos, active-inference, information-bottleneck, spin-glass, bft, renormalization-group, persistent-homology, functorial-semantics)

---

## Items Fase 9 (#62-#76)

| # | Item | Stato |
|---|------|-------|
| 62 | Mechanism Design (Vickrey/Myerson) | ✅ |
| 63 | MPC (Secure Multi-Party Computation) | ✅ |
| 64 | Correlated Equilibrium (Aumann 1974) | ✅ |
| 65 | Potential Games (Monderer-Shapley 1996) | ✅ |
| 66 | Contract Theory (Holmstrom 1979) | ✅ |
| 67 | Social Choice (Arrow/Gibbard) | ✅ |
| 68 | Prospect Theory (Kahneman-Tversky 1979) | ✅ |
| 69 | Regret Matching (Hart-Mas-Colell 2000) | ✅ |
| 70 | PID Control (Ziegler-Nichols) | ✅ |
| 71 | MPC (Model Predictive Control) | ✅ |
| 72 | TDT/FDT Reflective Dispatch | ✅ |
| 73 | Reinforcement Learning (Q-Learning) | ✅ |
| 74 | Attention Schema (Graziano) | ✅ |
| 75 | Free Energy Principle (Friston) | ✅ |
| 76 | Superrationality (Hofstadter) | ✅ |

## Items Fase 10 (#77-#86)

| # | Item | Stato |
|---|------|-------|
| 77 | Active Inference / FEP (Friston) | ✅ |
| 78 | Information Bottleneck (Tishby) | ✅ |
| 79 | MDL (Rissanen 1978) | ✅ |
| 80 | Renormalization Group (Wilson) | ✅ |
| 81 | Spin Glass / SA (Kirkpatrick) | ✅ |
| 82 | H∞ Robust Control (Zhou-Doyle) | ✅ |
| 83 | Byzantine Fault Tolerance (PBFT) | ✅ |
| 84 | Edge of Chaos (Langton) | ✅ |
| 85 | Persistent Homology (Edelsbrunner) | ✅ |
| 86 | Functorial Semantics (Mac Lane) | ✅ |
