# Fase 19 — Code Quality Intelligence & Defensive Autonomy (#167-#176)

**Tema**: I worker producono codice senza valutazione strutturale della qualità: nessun secret scanning sugli output, nessun assessment di reversibilità, nessun tracking di complessità o debito tecnico, nessuna analisi doc-code consistency. Fase 19 chiude tutti gli 8 P-gap rimasti completamente scoperti e trasforma il framework da "sistema che funziona" a "sistema che produce codice di cui un maintainer umano si fida".

→ Indice master: [PIANO.md](PIANO.md)

---

### Ordine implementazione Fase 19

```
Fase 19a (security & context, 6.5g):     #167 → #168 → #169
Fase 19b (execution intelligence, 4.5g): #170 → #171
Fase 19c (code quality, 7.0g):           #175 → #172 → #173
Fase 19d (debt & tools, 5.0g):           #174 → #176
```

### Riepilogo Fase 19 — Code Quality Intelligence & Defensive Autonomy (#167-#176)

| # | Titolo | Service | Sforzo | Valore | Tier |
|---|--------|---------|--------|--------|------|
| 167 | Output Secret Scanner | `SecretScannerService` | 2.0g | Alto | 0 |
| 168 | Reversibility Guard | `ReversibilityGuardService` | 2.5g | Alto | 0 |
| 169 | Project Instructions Injector | `ProjectInstructionsService` | 2.0g | Alto | 0 |
| 170 | Context Reminder Enricher | `ContextReminderEnricher` | 2.0g | Alto | 0 |
| 171 | Structured Progress Tracker | `StructuredProgressTracker` | 2.5g | Alto | 0 |
| 172 | Code Simplification Worker | `CodeSimplificationService` | 3.0g | Alto | 1 |
| 173 | Comment & Documentation Analyzer | `CommentAnalyzerService` | 2.0g | Medio-Alto | 1 |
| 174 | Technical Debt Estimator | `TechDebtEstimatorService` | 2.5g | Alto | 1 |
| 175 | Structural Complexity Gate | `ComplexityGateService` | 2.0g | Alto | 0 |
| 176 | Deferred Tool Loading Manager | `DeferredToolLoaderService` | 2.5g | Medio-Alto | 1 |
|   |     | **Totale Fase 19** | | **23.0g** | |

Documentazione completa: `docs/agent-framework/research-domains-ext.md` (§106-§115)

Claude Code patterns chiusi (Fase 19): P2, P5, P7, P14, P15, P19, P20, P26 (8 nuovi)
Cumulativo Fasi 17-19: 22/28 pattern coperti (✅ o 🔧). Residui: P16, P18 (bloccati #25), P23 (N/A)

### Sintesi ricerca accademica Fase 19 (S28, 2026-03-15)

10 report Template F completati. ~30 riferimenti validati, 4 fabbricati, ~60 paper aggiuntivi identificati.

#### Per-item summaries

**#167** — Secret Scanner: **Tutti e 4 i paper con errori**. Meli cit ~121 (non ~250). **Saha: titolo e venue completamente sbagliati** — "Secrets in Source Code" su IEEE COMSNETS 2020, non "Reducing FP" su CCS Workshop. **SecretBench: autori Basak non Feng**, anno 2023 non 2022, "818 repository" non "818 categorie". **"SecretLint" NON ESISTE**. Paper chiave: **Niu et al. TOSEM 2024** (LLM emettono credenziali memorizzate dal training data — giustificazione diretta). Architettura: **2 livelli** (regex+entropy → LLM validation).

**#168** — Reversibility Guard: **2 paper fabbricati** (Zhang FSE 2024, Bohme ICSE 2024 — **non esistono**). Weiser: anno **1981** (non 1984), cit ~1201 (non ~6000, 5x). Xia "Agentless" confermato (~289 cit). Sostituti: **ToolEmu** (Ruan et al., ICLR 2024, ~234 cit — safety per agenti con tool use), **AgentSpec** (ICSE 2026, runtime enforcement). Change impact analysis > program slicing per blast radius.

**#169** — Project Instructions: GPT-3 **sottostimato** (~55282, non ~30000). FLAN **sottostimato** (~4800, non ~3000). AgentIF confermato NeurIPS 2025 D&B. **"Model spec" è di OpenAI, non Anthropic** (Anthropic ha "Claude's Constitution"). Paper chiave: **Gloaguen et al. "Evaluating AGENTS.md"** (ETH Zurich 2026) — primo studio empirico: AGENTS.md seguiti ma **-3% success rate** per overhead. Implicazione: servizio parsimonioso. Gerarchia project>directory>file: **territorio inesplorato**, analogo a specificità CSS.

**#170** — Context Reminders: Liu "Lost in the Middle" anno **2023** (non 2024), cit **~3078** (non ~800, 4x). **"TALE" è una conflazione** con paper di visione — sostituire con **LongAttn (Wu et al., ACL Findings 2025)**. "Retrieval Head" autori **Wu** (non Xu), venue **ICLR 2025** (non ICML 2024). Pirolli cit ~3481 (non ~2500). Paper chiave: **Dongre et al. "Drift No More?" (arXiv 2025)** — formalizza context drift come processo stocastico. **Bui "Building AI Coding Agents" (arXiv 2026)** — implementazione production-grade di system reminders. Raccomandazione: trigger **event-driven** (non periodici), reminders come `role: user`.

**#171** — Progress Tracker: AgentBoard titolo corretto "Multi-turn LLM Agents", cit **~152** (non ~50, 3x). SWE-bench cit **~1667** (non ~300, 5.5x). MAgICoRe EMNLP 2025 confermato. Devin è prodotto commerciale (T7), non paper. Paper chiave: **TheAgentCompany** (Xu et al. 2024, partial completion score con checkpoint), **AOP** (Li et al., ICLR 2025, 3 principi decomposizione subtask), **SHIELDA** (Zhou et al. 2025, 36 tipi eccezione per workflow agentici). Progress rate AgentBoard ≡ Earned Value di EVM — #171 e #155 devono condividere ontologia.

**#172** — Code Simplifier: **Silva et al. ICSE 2025 NON ESISTE** — paper fabbricato. Alomar venue **IEEE TSE** (non EMSE), anno 2023/2024 (non 2022). McCabe cit **~5755-6000** (non ~10000). Finding critico: **Dristi & Dwyer (arXiv 2026) — 19-35% dei refactoring LLM alterano la semantica**, ~21% sfuggono ai test. Design deve includere behavior preservation verification. SWE-Refactor (arXiv 2026) come benchmark.

**#173** — Comment Analyzer: **Wen et al. ASE 2022 NON ESISTE** — paper fabbricato. Louis venue **ICSE-NIER 2020** (4 pagine, ~8 cit), non ICSE main. Tan cit ~320 (non ~200). **DeBERTa NLI sub-ottimale per coppie commento-codice** — codice fuori distribuzione. Raccomandazione: preprocessare codice in riassunto semantico NL prima del cross-encoder, oppure usare modelli code-aware (CodeBERT, CodeT5+). Paper chiave: **Zhang FSE 2024** (LLM + program analysis per CCI), **LLMCup** (arXiv 2025, +49-117% accuracy).

**#174** — Tech Debt Estimator: Tutti confermati. Cunningham ~1035 cit. Kruchten ~656 (non ~500). **Lenarduzzi venue: JSS 2021** (non EMSE). Avgeriou ~492 (non ~200, +146%). SQALE **non più best-in-class** — campo spostato verso ibridi behavioral+structural. Paper chiave: **ACE** (Tornhill & Borg, ICSE 2025) — loop automatizzato detection→refactoring→test→PR. **Manifesto 2025** (arXiv:2505.13009) successore del Dagstuhl 2016.

**#175** — Complexity Gate: **Tutti e 4 confermati**, correzioni minori. McCabe cit ~5755 (non ~10000). Campbell anno **2017** (non 2018). Spadini ~172 (non ~200). Sharma ~335 (non ~400). Soglia CC ≤ 15 **ben supportata** (NIST, NASA). Codice LLM ha CC mediano basso (1-3) — gate scatterà solo su outlier. Tree-sitter scelta allineata allo stato dell'arte. Raccomandazione: cognitive complexity come metrica di primo livello.

**#176** — Deferred Tool Loading: Tutti e 4 reali. **Gorilla è NeurIPS 2024** (non solo arXiv 2023). Citazioni non verificabili precisamente (S2 rate-limited). Paper chiave: **EASYTOOL** (NAACL 2025, ~153 cit) — compressione descrizioni tool, combinato con deferred loading dà riduzione **>95%**. **COLT** (CIKM 2024) — retrieval per completezza. Analogia con **Working Set Model di Denning** (1968, memoria virtuale).

#### Correzione critica cross-item

| Problema | Item | Azione |
|----------|------|--------|
| DeBERTa NLI su codice fuori distribuzione | #173 (anche #157, #163) | **Preprocessare codice → riassunto NL** prima del cross-encoder, oppure usare modelli code-aware (CodeT5+) |
| LLM refactoring altera semantica (19-35%) | #172 | **Behavior preservation verification obbligatoria** (differential testing o property-based testing) |

#### Correzioni algoritmiche

| # | Claim | Correzione |
|---|-------|------------|
| 167 | Regex + entropy sufficienti | **2 livelli**: regex+entropy (layer 1) + LLM validation (layer 2) |
| 168 | Program slicing per blast radius | **Change impact analysis** > program slicing per sistemi agentici |
| 169 | Iniettare tutte le instructions | **Parsimonioso**: AGENTS.md riduce success rate del ~3% per overhead |
| 170 | Trigger periodico (ogni N tool calls) | **Event-driven** con guardrail counters. Reminders come `role: user` |
| 171 | Progress solo osservato | Distinguere **progress claimed vs verified**. Validare decomposizione con principi AOP |
| 172 | 5 pass senza verifica | **Behavior preservation obbligatoria**: 19-35% refactoring LLM altera semantica |
| 173 | DeBERTa NLI diretto su codice | **Preprocessare codice → NL** o usare CodeT5+ |
| 174 | SQALE-inspired | SQALE non best-in-class — **ibrido behavioral+structural** (CodeScene/ACE pattern) |
| 175 | Solo CC come metrica primaria | **Cognitive complexity** come metrica di primo livello accanto a CC |
| 176 | Solo deferred loading | **EASYTOOL compressione + deferred loading** per riduzione >95% |

#### Tabella correzioni citazioni (S2 vs claim, solo delta significativi)

| Paper | Claim | Verificato | Delta | Item |
|-------|-------|------------|-------|------|
| Meli | ~250 | ~121 | **-52%** | 167 |
| Weiser | ~6000 | ~1201 | **-80%** | 168 |
| GPT-3 (Brown) | ~30000 | ~55282 | **+84%** | 169 |
| FLAN (Wei) | ~3000 | ~4800 | **+60%** | 169 |
| Liu "Lost in Middle" | ~800 | ~3078 | **+285%** | 170 |
| AgentBoard (Ma) | ~50 | ~152 | **+204%** | 171 |
| SWE-bench (Jimenez) | ~300 | ~1667 | **+456%** | 171 |
| McCabe | ~10000 | ~5755 | **-42%** | 172,175 |
| Tan "iComment" | ~200 | ~320 | +60% | 173 |
| Avgeriou | ~200 | ~492 | **+146%** | 174 |

5/10 sovrastimati, 5/10 sottostimati. Pattern inverso rispetto a Fase 18 (dove 9/10 erano sovrastimati).

#### Correzioni venue/autore

| Paper | Errore | Correzione | Item |
|-------|--------|------------|------|
| Saha et al. | Titolo + venue completamente sbagliati | **"Secrets in Source Code"**, IEEE COMSNETS 2020 | 167 |
| SecretBench | Autori Feng et al. | **Basak** et al., 2023 non 2022 | 167 |
| Weiser | Anno 1984 | **1981** | 168 |
| "Model spec" | Anthropic | **OpenAI** | 169 |
| Liu "Lost in Middle" | Anno 2024 | **2023** | 170 |
| "TALE" He et al. | ACL 2025 | **Conflazione** — sostituire con LongAttn (Wu et al., ACL Findings 2025) | 170 |
| "Retrieval Head" | Xu et al., ICML 2024 | **Wu** et al., **ICLR 2025** | 170 |
| AgentBoard | "Multi-step Reasoning Agent" | "**Multi-turn LLM Agents**" | 171 |
| Alomar | EMSE 2022 | **IEEE TSE 2023/2024** | 172 |
| Louis | ICSE 2020 | **ICSE-NIER 2020** (4 pagine) | 173 |
| Lenarduzzi | EMSE 2021 | **JSS 2021** | 174 |
| Campbell | 2018 | **2017** (white paper); conferenza TechDebt 2018 | 175 |
| Gorilla (Patil) | arXiv 2023 | **NeurIPS 2024** | 176 |

#### Paper fabbricati (4 su ~30 validati = 13%)

| Paper | Item | Sostituto |
|-------|------|-----------|
| Zhang et al. "Irreversible Decisions" FSE 2024 | 168 | ToolEmu (Ruan et al., ICLR 2024) |
| Bohme et al. "Assurance Cases" ICSE 2024 | 168 | AgentSpec (ICSE 2026) |
| Silva et al. "LLM Refactoring" ICSE 2025 | 172 | Dristi & Dwyer (arXiv 2026) |
| Wen et al. "Stale Comments" ASE 2022 | 173 | Zhang (FSE 2024, LLM+CCI) |
| "SecretLint" arXiv 2023 | 167 | Basak et al. "Comparative Study" (ESEM 2023) |

#### Paper chiave scoperti (T1 top-10)

| Paper | Venue | Item | Perché |
|-------|-------|------|--------|
| Niu et al. "Your Code Secret Belongs to Me" | TOSEM 2024 | 167 | LLM emettono credenziali memorizzate — giustificazione empirica diretta |
| ToolEmu (Ruan et al.) | ICLR 2024 | 168 | Safety evaluation per agenti con tool use, ~234 cit |
| Gloaguen "Evaluating AGENTS.md" | ETH Zurich 2026 | 169 | Primo studio empirico su file AGENTS.md/CLAUDE.md |
| Dongre "Drift No More?" | arXiv 2025 | 170 | Formalizza context drift come processo stocastico |
| Bui "Building AI Coding Agents" | arXiv 2026 | 170 | Production-grade system reminders con 8 event detectors |
| TheAgentCompany (Xu et al.) | 2024 | 171 | Partial completion score con checkpoint intermedi |
| Dristi & Dwyer | arXiv 2026 | 172 | 19-35% refactoring LLM altera semantica |
| ACE (Tornhill & Borg) | ICSE 2025 | 174 | Loop automatizzato detection→refactoring→test→PR |
| EASYTOOL | NAACL 2025 | 176 | Compressione descrizioni tool, ~153 cit |
| AOP (Li et al.) | ICLR 2025 | 171 | 3 principi decomposizione subtask |

#### Cross-connessioni dalla ricerca

| Connessione | Implicazione |
|-------------|-------------|
| NLI preprocessing (#173) → (#157, #163) | Stesso problema: DeBERTa fuori distribuzione su codice. Tutti e 3 necessitano preprocessing code→NL |
| Behavior preservation (#172) → (#154) | ValidationPipelineService deve verificare equivalenza semantica post-semplificazione |
| AGENTS.md overhead (#169) → (#107) | ContextWindowManager deve budgetare instructions con parsimonia |
| Event-driven reminders (#170) → (#136) | BayesianSurpriseDetector come trigger, non timer periodico |
| Progress ≡ EVM (#171) → (#155) | Condividere ontologia metriche tra reporting attivo e stima passiva |
| EASYTOOL (#176) + deferred → (#107) | Riduzione >95% token budget tool descriptions |
| 2-layer secret scan (#167) → Ollama | LLM validation come layer 2 sfrutta infrastruttura Ollama esistente |
| Working Set (#176) → Denning 1968 | Replacement policies OS applicabili a tool eviction |

Report: `docs/research/{secret-scanner-167,reversibility-guard-168,project-instructions-169,context-reminders-170,progress-tracker-171,code-simplifier-172,comment-analyzer-173,tech-debt-174,complexity-gate-175,deferred-tools-176}.md`
