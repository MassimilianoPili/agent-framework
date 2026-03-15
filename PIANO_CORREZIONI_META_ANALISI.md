# Piano Correzioni — Meta-Analisi Session 15 Marzo 2026

Piano da eseguire per consolidare le correzioni e il lavoro residuo dalla sessione di meta-analisi.

---

## 1. CORREZIONI GIÀ APPLICATE (verificare in HEAD)

### 1.1 InsightExtractor — getter mismatch (COMPILAZIONE)
**File**: `control-plane/orchestrator/src/main/java/com/agentframework/orchestrator/knowledge/InsightExtractor.java`
**Problema**: Usava `getDependencies()` e `getRetryCount()` ma `PlanItem` espone `getDependsOn()` e `getContextRetryCount()`.
**Fix applicato**: Rinominati i 4 call site (righe 176, 196, 200).
**Verifica**: `mvn compile -pl control-plane/orchestrator` deve passare senza errori.

### 1.2 HedgeAlgorithmPropertyTest — jqwik BigDecimal scale
**File**: `control-plane/orchestrator/src/test/java/com/agentframework/orchestrator/analytics/HedgeAlgorithmPropertyTest.java`
**Problema**: jqwik `@DoubleRange(min = 0.001)` richiede scale 3, ma il default è 2. Anche `Arbitraries.doubles().between(0.001, ...)` ha lo stesso limite.
**Fix applicato**:
- Rimosso import `net.jqwik.api.constraints.DoubleRange`
- Sostituiti 3 `@DoubleRange` con `@Provide` arbitraries: `learningRate()` (0.01–2.0), `wideEta()` (0.01–5.0), `smallEta()` (0.01–1.0)
**Verifica**: `mvn test -pl control-plane/orchestrator -Dtest="HedgeAlgorithmPropertyTest"` → 10/10 pass.

### 1.3 Property-Based Tests — 23 test verdi
**File nuovi** (3):
- `ShapleyValuePropertyTest.java` — 6 proprietà (efficiency, symmetry, null player, additivity, MC convergence, Banzhaf)
- `WassersteinDistancePropertyTest.java` — 7 proprietà (non-negatività, identità, simmetria, triangle inequality, translation, scale, delta)
- `HedgeAlgorithmPropertyTest.java` — 10 proprietà (uniform sum, update sum, non-negative, multi-round, zero-loss, ordering, learning rate, regret bound, sublinear, selectExpert)
**Verifica**: `mvn test -pl control-plane/orchestrator -Dtest="ShapleyValuePropertyTest,WassersteinDistancePropertyTest,HedgeAlgorithmPropertyTest"` → 23/23.

### 1.4 CI/CD Pipeline (Gitea Actions)
**File nuovi** (2):
- `.gitea/workflows/ci.yml` — Build & Test (mvn verify) + Migration Check (Flyway su PG reale con pgvector+AGE)
- `.gitea/workflows/release.yml` — Docker image build su tag v*
**Dipendenza POM**: `flyway-maven-plugin` aggiunto a `control-plane/orchestrator/pom.xml` con driver PostgreSQL.
**Nota**: Il job `migration-check` usa l'immagine custom `sol/postgres:pg18-age` dal registry Gitea. Verifica che il secret `REGISTRY_URL` sia configurato in Gitea.

### 1.5 jqwik Dependency
**File**: `control-plane/orchestrator/pom.xml`
**Aggiunto**: `net.jqwik:jqwik:1.9.2` scope test.

---

## 2. LAVORO RESIDUO (da completare)

### 2.1 A5 — Template Externalization per Academic Researcher
**Status**: Preparato nella sessione precedente ma NON committato nel repo agent-framework. I file template sono stati creati in `agents/templates/` e la definizione agente ridotta da 886 a 472 righe.
**File da creare** (se non esistono):
- `agents/templates/template-a-survey.md` (Survey/Literature Review)
- `agents/templates/template-b-paper-analysis.md` (Paper Analysis)
- `agents/templates/template-c-open-problem.md` (Open Problem)
- `agents/templates/template-d-concept.md` (Concept Clarification)
- `agents/templates/template-e-causal-claim.md` (Causal Claim/Controversy)
- `agents/templates/template-f-design-validation.md` (Design Validation Report)
- `agents/templates/search-endpoints.md` (URL search endpoints)
- `agents/templates/blog-sources.md` (34 blog curati)
- `agents/templates/source-routing.md` (Domain→source routing table)
**Nota**: Questa è roba dell'`academic-researcher` agent, non del framework Java. I template sono in `/data/massimiliano/claude-shared/agents/templates/` (shared storage), NON in agent-framework. Verificare prima di agire.

### 2.2 KORE — Aggiornamento Nodi
Aggiornare il knowledge graph AGE con:
1. **CICDPipeline** nodes: `agent-framework-ci`, `agent-framework-release` (se non già presenti)
2. **PropertyTest** metadata: 3 file, 23 proprietà, copertura Shapley/Wasserstein/Hedge
3. **InsightExtractor** bug fix registrato

### 2.3 `.jqwik-database` — Gitignore
Il file `control-plane/orchestrator/.jqwik-database` è untracked. Aggiungere al `.gitignore`:
```
# jqwik property-based testing database
.jqwik-database
```

---

## 3. ITEM META-ANALISI COMPLETATI (riepilogo)

| ID | Titolo | Commit | Note |
|----|--------|--------|------|
| C4 | BOCPD → GP Integration | f3cbcc1 | Wire BOCPD in TaskOutcomeService |
| C3 | Task-Type Classifier | dfe3830 | Classificazione cognitiva STYLE/REASONING/VERIFICATION/EXPLORATION |
| D2 | CI/CD Pipeline | 9208df7 | ci.yml + release.yml + flyway-maven-plugin |
| D1 | Property-Based Testing | 9208df7 | 3 file, 23 proprietà, jqwik 1.9.2 |
| A2 | Dark Bean Analytics Integration | 3954271, b110c3c, 2dcae47 | 18 servizi wired in 4 fasi |

---

## 4. PROSSIMI ITEM META-ANALISI (priorità)

Dalla tabella priorità del piano originale, i prossimi in ordine:

| # | ID | Proposta | Effort | Prerequisiti |
|---|-----|----------|--------|-------------|
| 1 | **B4** | Framework deployment su SOL | M | B1 completato |
| 2 | **B1** | Memory pressure mitigation | S | Nessuno |
| 3 | **B2** | Prometheus monitoring | M | B4 completato |
| 4 | **D5** | Eval harness expansion | M | B4 per test end-to-end |
| 5 | **C2** | External feedback loop | S-M | B4 per feedback reale |
| 6 | **A1** | OrchestrationService decomposition | L | Feedback da B4 |
| 7 | **A3** | Council sycophancy mitigation | M | Council session reale |

**Nota critica**: B4 (deploy su SOL) rimane il gate principale. Senza deploy reale, tutte le verifiche sono teoriche. Il commit e1716d3 ha già un `docker-compose.sol.yml` e fix di startup, ma non è mai stato avviato.

---

## 5. BUG NOTI DA MONITORARE

1. **InsightExtractor**: Fix applicato ma il servizio non è mai stato invocato a runtime. Verificare al primo deploy.
2. **CrossPlanKnowledgeEngine** (commit 9208df7): Nuovo, `matchIfMissing=false`. Da attivare solo dopo deploy e primi piani completati.
3. **SelfImprovingOptimizerService** (commit 9208df7): Canary evaluator con z-test. Richiede dati storici per funzionare.
4. **Flyway migration count**: CI aspetta esattamente 36 migrazioni (V1–V38, alcune possono essere R__ repeatable). Se aggiungi migrazioni, aggiorna `EXPECTED=36` in `ci.yml`.
