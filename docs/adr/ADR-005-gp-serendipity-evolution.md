# ADR-005: Gaussian Process + Serendipita' — Evoluzione Architetturale

## Status

**Proposta** — roadmap items #11-#15, non ancora implementati.

## Context

Il sistema di reward e' operativo (S4): `RewardComputationService` aggrega 3 segnali,
`EloRatingService` calcola ranking per profilo, `PreferencePairGenerator` produce coppie DPO.
Tuttavia, questi segnali vengono solo *raccolti* — nessuna decisione operativa li usa in tempo reale.

Questo ADR documenta le scelte architetturali per 5 feature che chiudono il ciclo
feedback → decisione, usando Gaussian Process (GP) come modello probabilistico unificante.

---

## Decision 1: GP per Worker Selection (#11)

### Problema

`OrchestrationService.dispatchReadyItems()` (riga 523) assegna il profilo staticamente:

```java
// OrchestrationService.java:523-530
if (item.getWorkerProfile() == null) {
    String defaultProfile = profileRegistry.resolveDefaultProfile(item.getWorkerType());
    if (defaultProfile != null) {
        item.setWorkerProfile(defaultProfile);
    }
}
```

`WorkerProfileRegistry.resolveDefaultProfile()` (riga 149) fa solo `defaults.get(workerType.name())`.
Un "Build REST API" e un "Implement WebSocket handler" ricevono entrambi `be-java`.

### Perche' GP e non classificatore/regressore

| Alternativa | Problema |
|-------------|----------|
| **Classificatore** (e.g. Random Forest) | Ritorna solo la classe (be-java vs be-go), senza incertezza. Con 10 sample, predice con 99% di confidenza — non sa di non sapere. |
| **Regressore** (e.g. Linear Regression) | Ritorna un valore puntuale. Non distingue "predico 0.8 con fiducia" da "predico 0.8 perche' non ho dati". |
| **GP** | Ritorna `(mu, sigma^2)`. `sigma^2` alto = "non ho visto task simili" → trigger per REVIEW, cautela nel budget. Cold-start: con 0 dati, degenera al prior (media globale = default attuale). |

Il kernel RBF su embedding garantisce che task semanticamente simili condividano la predizione.
Non richiede feature engineering manuale.

### Perche' riga 523 e' l'unico punto di inserzione

Il profilo viene usato immediatamente dopo l'assegnazione:
- Riga 533: `profileRegistry.getProfileEntry(item.getWorkerProfile())` — capability check
- Riga 570: inserito nell'`AgentTask` — determina il routing del messaggio
- Riga 583: `taskProducer.dispatch(task)` — messaggio gia' inviato

Dopo il dispatch non e' possibile cambiare worker. Il GP *deve* operare prima della riga 523.

### Perche' `task_outcomes` come tabella separata

`plan_items` (entity JPA) non ha:
- **Embedding** (vector 1024 dim) — richiederebbe pgvector + modifica entity
- **ELO snapshot al dispatch** — ELO corrente ≠ ELO al momento della decisione
- **Predizione GP** — `mu`, `sigma^2` al momento del dispatch

Queste colonne hanno semantica OLAP (training), non OLTP (dispatch).
`ON DELETE SET NULL` su `plan_item_id` preserva lo storico training dopo delete piano.

### Perche' modulo `shared/gp-engine`

Il GP serve sia nell'orchestratore (worker selection) che nel context-manager (serendipita', #12).
Estrarlo in un modulo condiviso segue il pattern `shared/rag-engine`:
- Testabilita': GP e' matematica pura (kernel, cholesky, posterior), zero side-effect
- Swap: sostituire l'implementazione (e.g. GPyTorch, scikit-learn binding) senza toccare i consumer

---

## Decision 2: Serendipita' nel Context Manager (#12)

### Problema

`HybridSearchService` (shared/rag-engine) trova file per similarita' semantica (coseno + BM25).
Non scopre file storicamente utili ma semanticamente distanti.

### Perche' il residual del GP (non random)

`residual(file, task) = actual_usefulness - predicted_usefulness`

Un file con residual >> 0 e' stato *sorprendentemente* utile — pattern latente non catturato
dalla similarita' semantica. Esempio: `SecurityConfig.java` utile per un task "build CRUD API"
suggerisce vincoli di sicurezza impliciti nel progetto.

### Perche' nel Context Manager e non nel RAG_MANAGER

Il DAG dei task ha quest'ordine:
```
TASK_MANAGER → CONTEXT_MANAGER → RAG_MANAGER → domain workers
```

La serendipita' arricchisce il contesto *prima* del RAG. Il RAG puo' poi cercare anche
sui file "sorpresa". Se fosse nel RAG, il CM non ne beneficerebbe e il layering sarebbe
invertito (semantico prima dello storico).

### Come si compone con RRF

`HybridSearchService.java` fa RRF con k=60:
```
score(d) = 1/(k + rank_cosine) + 1/(k + rank_bm25)
```

La serendipita' aggiunge una terza ranked list:
```
score(d) = 1/(k + rank_cosine) + 1/(k + rank_bm25) + 1/(k + rank_serendipity)
```

RRF e' rank-based, non score-based — la terza sorgente si integra naturalmente.

---

## Decision 3: Council Taste Profile (#13)

### Problema

`CouncilService.conductPrePlanningSession()` (riga 97) e' stateless:

```java
// CouncilService.java:97-120
public CouncilReport conductPrePlanningSession(String spec) {
    // Enriches spec via RAG, selects members, consults parallel, synthesizes
    // ... ma non impara da piani passati
}
```

Non sa che piani "CRUD API" con 3 task hanno reward medio 0.85, mentre quelli con 5 task
hanno 0.65 (overhead coordinamento). Ogni piano parte da zero.

### Perche' GP e non regola statica

Una regola "CRUD = 3 task" non generalizza. Il GP interpola: spec "mezzo CRUD, mezzo real-time"
riceve predizione pesata. `sigma^2` dice al Council quando la predizione e' affidabile
(molti piani simili visti) vs quando serve cautela (spec nuova).

### Perche' nel Council e non nel Planner

Il Council opera *prima* del Planner. Il flusso e':

```
spec → Council (advisory) → CouncilReport → Planner (execution) → PlanItems
```

Il taste profile arricchisce il report con raccomandazione sulla struttura
("basandomi su piani passati simili, suggerisco 3-4 task con REVIEW finale").
Il planner *decide* se seguirla — il Council non ha potere di veto.
Separazione: Council = advisory (consiglia), Planner = execution (decide).

---

## Decision 4: DPO con GP Residual (#14)

### Problema

`PreferencePairGenerator` (riga 113) filtra con delta statico:

```java
// PreferencePairGenerator.java:113
private static final double MIN_DELTA = 0.3;
```

Non distingue coppie *ovvie* da coppie *informative*:
- **Ovvia**: be-java su "REST API" → reward 0.9 (GP predice 0.85) → residual ≈ 0.
  Il modello DPO gia' sa che be-java e' buono su REST API.
- **Informativa**: be-java su "WebSocket" → reward 0.85 (GP predice 0.3) → residual 0.55.
  Il modello DPO NON sa che be-java eccelle su WebSocket — questa coppia insegna.

### Perche' nuova strategia e non modifica delle esistenti

Le 2 strategie attuali hanno logica propria e funzionano:

1. `same_plan_cross_profile` (riga 75): confronta profili diversi sullo stesso piano
2. `retry_comparison` (riga 103): confronta successo vs fallimento precedente

Aggiungere `gp_residual_surprise` e' additivo (Open/Closed Principle).
Non modifica il flusso delle strategie esistenti.

### Perche' campo `gpResidual` su PreferencePair

Il trainer DPO puo' fare importance sampling pesando per `|gpResidual|`.
Coppie con alto residual ricevono peso maggiore nel training.
Senza il campo, l'informazione si perde dopo la generazione della coppia.

---

## Decision 5: Active Learning per Token Budget (#15)

### Problema

`TokenBudgetService.checkBudget()` (riga 53) confronta `used >= limit`:

```java
// TokenBudgetService.java:53-70
public BudgetDecision checkBudget(UUID planId, String workerType, Budget budget) {
    long used = getUsage(planId, workerType);
    long limit = budget.perWorkerType().getOrDefault(workerType, Long.MAX_VALUE);
    if (used >= limit) { ... }
}
```

Il limite e' statico. Task facili sprecano budget (limite troppo alto),
task difficili lo esauriscono (limite troppo basso).

### Formula

```
dynamic_budget = base × (1 + alpha × sigma^2) × clip(mu, 0.3, 1.0)
```

- `sigma^2` alto (incertezza) → piu' budget (active learning: il task potrebbe
  rivelare pattern nuovi per il GP, vale la pena investire)
- `mu` basso (qualita' attesa bassa) → riduci budget (non sprecare su task
  destinato a fallire)
- `clip(mu, 0.3, 1.0)` impedisce di azzerare il budget

### Perche' in `checkBudget` e non in `recordUsage`

`checkBudget` opera *prima* del dispatch (OrchestrationService riga 465).
`recordUsage` opera *dopo* (riga 280). Il budget dinamico deve modulare
il limite *prima* che il task parta.

### Enforcement mode invariato

Il budget dinamico modifica solo il limite numerico. La policy
(`FAIL_FAST`, `NO_NEW_DISPATCH`, `SOFT_LIMIT`) resta invariata.
Single Responsibility: la policy dice *cosa fare* quando il budget e' superato,
il GP dice *quanto* budget dare.

---

## Schema Database (`task_outcomes`)

```sql
CREATE TABLE task_outcomes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_item_id    UUID REFERENCES plan_items(id) ON DELETE SET NULL,
    plan_id         UUID NOT NULL,
    task_key        VARCHAR(20) NOT NULL,
    worker_type     VARCHAR(20) NOT NULL,
    worker_profile  VARCHAR(50),
    task_embedding  vector(1024),          -- mxbai-embed-large via Ollama
    elo_at_dispatch FLOAT,                 -- snapshot ELO al momento del dispatch
    gp_mu           FLOAT,                 -- predizione GP al dispatch
    gp_sigma2       FLOAT,                 -- incertezza GP al dispatch
    actual_reward   FLOAT,                 -- plan_items.aggregated_reward post-completion
    created_at      TIMESTAMP DEFAULT now()
);

CREATE INDEX idx_task_outcomes_embedding ON task_outcomes
    USING hnsw (task_embedding vector_cosine_ops);
CREATE INDEX idx_task_outcomes_worker ON task_outcomes (worker_type, worker_profile);
```

`ON DELETE SET NULL` su `plan_item_id`: il piano puo' essere cancellato,
ma lo storico di training resta (serve al GP per apprendere).

---

## Flusso Dati Completo

```
                     ┌─────────────────────────┐
                     │   OrchestrationService   │
                     │   dispatchReadyItems()   │
                     │        riga 523          │
                     └────────┬────────────────┘
                              │
                    ┌─────────▼─────────┐
                    │  GP Engine (mu,σ²) │ ◄─── task_outcomes (training data)
                    └─────────┬─────────┘
                              │
              ┌───────────────┼───────────────────┐
              ▼               ▼                   ▼
     Worker Selection   Token Budget       Council Taste
     (#11, riga 523)   (#15, riga 465)    (#13, riga 97)
              │               │                   │
              ▼               ▼                   ▼
         [dispatch]      [checkBudget]     [CouncilReport]
              │               │                   │
              ▼               ▼                   ▼
         [worker executes]    ...          [planner decomposes]
              │
              ▼
     RewardComputationService
     (riga 209-267)
              │
              ▼
     EloRatingService ──► aggiorna ELO profilo
              │
              ▼
     PreferencePairGenerator
     + gp_residual_surprise (#14)
              │
              ▼
     task_outcomes ──► ri-training GP ──► loop chiuso
```

---

## Rischi e Mitigazioni

| Rischio | Mitigazione |
|---------|-------------|
| Cold start (0 dati) | GP degenera al prior = media globale → equivalente al default statico attuale |
| Embedding drift (modello Ollama cambiato) | `task_outcomes.task_embedding` e' denormalizzato — ri-embedding batch offline |
| GP lento su N grande | Subset training (ultimi 500 task), kernel approssimato (Random Fourier Features) |
| Overfitting su pochi profili | Kernel ARD (Automatic Relevance Determination) — feature irrilevanti hanno lengthscale → ∞ |

## Fonti

- C.E. Rasmussen & C.K.I. Williams, *Gaussian Processes for Machine Learning*, MIT Press 2006
- D. Duvenaud, *The Kernel Cookbook*, 2014
- R. Garnett, *Bayesian Optimization*, Cambridge University Press 2023
- Bradley-Terry model: gia' implementato in `EloRatingService.java` (formula riga 89-94)
