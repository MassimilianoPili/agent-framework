# Fase 7 — Research Domains Fase 11-12 (#87-#106)

> Formal Methods, Social Choice, Cybernetics, Learning Theory, Compressed Sensing,
> Ergodic Economics, Queuing Theory, Bayesian Surprise, Process Mining, Actor Model,
> Chandy-Lamport, Fixed-Point, LTL, Description Logic, Information Foraging, Stigmergy.
> Sessioni S20 (inclusa in S8-S12), Audit qualita' S22.

---

## Items Fase 11 (#87-#96)

| # | Dominio | Titolo | Sforzo | Valore | Stato |
|---|---------|--------|--------|--------|-------|
| 87 | Formal Methods | Petri Nets (plan concurrency analysis) | 2.5g | Alto | ✅ |
| 88 | Formal Methods | CSP (worker communication protocol) | 2.5g | Alto | ✅ |
| 89 | Learning Theory | PAC-Bayes (GP convergence bounds) | 2.0g | Alto | ✅ |
| 90 | Social Choice | Social Choice Theory (council aggregation) | 2.5g | Alto | ✅ |
| 91 | Social Systems | Diversity Prediction (council composition) | 2.0g | Alto | ✅ |
| 92 | Cybernetics | VSM (viable system model, agent hierarchy) | 2.5g | Medio-Alto | ✅ |
| 93 | Learning Theory | Thompson Sampling (Bayesian exploration) | 2.0g | Alto | ✅ |
| 94 | Compressed S. | Compressed Sensing (sparse RAG retrieval) | 2.5g | Alto | ✅ |
| 95 | Economics | Ergodic Economics (time-average budgets) | 2.0g | Medio-Alto | ✅ |
| 96 | Queuing Theory | M/G/1 (stream capacity planning) | 2.0g | Alto | ✅ |
|   |  | **Totale Fase 11** | **22.5g** | | |

### Ordine implementazione

```
Fase 11a (formal+voting, ~9.5g):   #87 ✅ → #90 ✅ → #89 ✅ → #88 ✅
Fase 11b (council+econ, ~6.5g):    #91 ✅ → #95 ✅ → #96 ✅
Fase 11c (avanzato, ~6.5g):        #93 ✅ → #94 ✅ → #92 ✅
```

### Dipendenze critiche Fase 11

- **CouncilService** target di 3 items: #90 (voting protocol), #91 (diversity), #92 (VSM S4)
- **Redis Streams** target di 2 items: #88 (CSP protocol verification), #96 (queuing capacity)
- **RAG HybridSearchService** target di #94 (compressed sensing)
- **#87 Petri Nets + #88 CSP complementari**: stato (places/tokens) vs processo (events/channels)
- **#93 Thompson + #89 PAC-Bayes**: Thompson esplora campionando dalla GP posterior, PAC-Bayes bounds quando smettere
- **#95 Ergodic + #69 Kelly**: Kelly e' caso speciale ergoico
- **#92 VSM meta-architettura**: mappa il framework sui 5 sottosistemi di Beer (S1=Worker, S2=Redis, S3=Orchestration, S4=Council, S5=Human)

---

## Items Fase 12 (#97-#106)

| # | Dominio | Titolo | Sforzo | Valore | Stato |
|---|---------|--------|--------|--------|-------|
| 97 | Bayesian | Bayesian Surprise (serendipity detection) | 2.0g | Alto | ✅ |
| 98 | Process | Process Mining (workflow discovery) | 2.5g | Alto | ✅ |
| 99 | RL Theory | Potential-Based Reward Shaping | 2.0g | Alto | ✅ |
| 100 | Concurrency | Actor Model (worker isolation) | 2.5g | Alto | ✅ |
| 101 | Distributed | Chandy-Lamport Snapshots (plan checkpointing) | 2.0g | Alto | ✅ |
| 102 | Analysis | Fixed-Point Theory (iterative convergence) | 2.0g | Alto | ✅ |
| 103 | KR | Description Logic ALC (capability matching) | 2.0g | Medio-Alto | ✅ |
| 104 | HCI/IR | Information Foraging (RAG optimization) | 2.5g | Alto | ✅ |
| 105 | Logic | LTL (hook policies, temporal verification) | 2.0g | Alto | ✅ |
| 106 | Swarm | Stigmergy (coordinamento indiretto) | 2.0g | Medio-Alto | ✅ |
|   |  | **Totale Fase 12** | **21.5g** | | |

### Ordine implementazione

```
Fase 12a (core, ~12.0g):          #97 ✅ → #99 ✅ → #100 ✅ → #101 ✅ → #102 ✅ → #105 ✅
Fase 12b (avanzato, ~9.5g):       #98 ✅ → #103 ✅ → #104 ✅ → #106 ✅
```

### Dipendenze critiche Fase 12

- **#98 Process Mining → #87 Petri Nets**: il workflow net scoperto dal process mining E' un Petri net
- **#100 Actor Model → #88 CSP**: complementari — Actor per isolamento, CSP per sincronizzazione
- **#104 Info Foraging → #94 Compressed Sensing**: entrambi ottimizzano retrieval RAG
- **#106 Stigmergy → #92 VSM**: meccanismo di coordinamento S2 nel VSM
- **GP Engine (#15)** prerequisito per #97, #99, #102 (prior/posterior, reward, convergenza)
- **OrchestrationService** target di #100, #101, #105, #106

---

## Sessione 22 — Audit Qualita' Fase 9-12 (2026-03-10)

### Risultato audit

Audit sistematico di tutte le 45 implementazioni research-domain (#62-#106):

| Fase | GENUINE | ACCEPTABLE | STUB |
|------|---------|------------|------|
| 9 (#62-#76) | 15/15 | 0 | 0 |
| 10 (#77-#86) | 9/10 | 1 (#86) | 0 |
| 11 (#87-#96) | 10/10 | 0 | 0 |
| 12 (#97-#106) | 9/10 | 0 | 1 (#100) |
| **Totale** | **43** | **1** | **1** |

Classificazione:
- **GENUINE**: implementa l'algoritmo reale dal paper
- **ACCEPTABLE**: semplificato ma cattura l'insight core
- **STUB**: query+mean+DTO con naming cosmetico

### Fix applicati (stesso giorno)

1. **#100 ActorModelSupervisor — REWRITE** (STUB → GENUINE)
   - Prima: calcolo crash-rate + soglie, nessuno stato
   - Dopo: supervision tree con ChildActor (state machine RUNNING→CRASHED→RESTARTING→STOPPED),
     restart policy (maxRestarts in time window), escalation on limit exceeded,
     REST_FOR_ONE strategy con ordine di registrazione, restart event history
   - 11 test (erano 7)

2. **#86 FunctorialSemanticsService — BUG FIX** (ACCEPTABLE → GENUINE)
   - Bug: `error = |compositeAC - (edgeAB + edgeBC)|` era una tautologia algebrica (sempre 0)
     perche' `(fc-fa) - ((fb-fa)+(fc-fb)) = 0` per telescoping sum
   - Fix: confronta functor (GP prediction) vs actual reward su path compositi:
     `error = |(gp_mu(C)-gp_mu(A)) - (actual(C)-actual(A))|`
   - 7 test (erano 5), inclusi test di non-zero error e non-compositional detection

3. **#101 ChandyLamportSnapshotter — IMPROVEMENT** (ACCEPTABLE → GENUINE)
   - Prima: solo task_outcomes + PlanEvent, nessun local state reale
   - Dopo: PlanItemRepository per stato locale reale (WAITING/DISPATCHED/RUNNING/DONE/FAILED),
     marker sequence (ultimo PlanEvent.sequenceNumber), state-event coherence check
   - 8 test (erano 6), inclusi coherence violation e mixed states

### Test

1082 test totali (+8 netti), 0 fallimenti.

**Commit**: `5d733f8`

---

## Arricchimenti inclusi (Fasi 9-12)

Oltre ai 45 items, `research-domains-new.md` contiene:
- **10 pseudocodice** (items algoritmici: #62, #63, #67, #77, #81, #87, #89, #93, #94, #96)
- **10 esempi numerici** (con dati concreti dal framework)
- **15 implementation sketches** (Java signatures + data flow)
- **27 cross-reference "Vedi anche"** (bidirezionali tra items correlati)
