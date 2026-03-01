-- ============================================================================
-- V6: RAG Graph (Apache AGE)
-- Estensione Apache AGE per graph database su PostgreSQL.
-- Due grafi separati per dominio: knowledge (semantico) e code (strutturale).
-- Richiede immagine PostgreSQL custom: sol/postgres:pg16-age
--
-- NOTA: Apache AGE non supporta COMMENT ON per grafi/nodi/archi.
-- I grafi sono strutture interne gestite da ag_catalog, non tabelle PostgreSQL.
-- La documentazione dello schema e' nei commenti SQL qui sotto.
-- ============================================================================

-- Attiva l'estensione AGE (compilata nel Dockerfile custom sol/postgres:pg16-age)
CREATE EXTENSION IF NOT EXISTS age;

-- Carica le funzioni AGE nella sessione e imposta il search_path
LOAD 'age';
SET search_path = ag_catalog, "$user", public;

-- ── Grafo 1: knowledge_graph ────────────────────────────────────────────────
-- Grafo semantico per relazioni tra chunk, concetti, decisioni e task.
--
-- Nodi:
--   :Chunk     {id, filePath, content}     — frammento di codice/documento indicizzato
--   :Concept   {name, description}         — concetto estratto (es. "dependency injection")
--   :Decision  {title, rationale, date}    — decisione architetturale
--   :Task      {taskKey, planId, status}   — task dal piano di orchestrazione
--
-- Archi:
--   :REFERENCES   (Chunk)->(Chunk)         — un chunk cita un altro
--   :DEPENDS_ON   (Chunk)->(Concept)       — un chunk dipende da un concetto
--   :PART_OF      (Chunk)->(Decision)      — un chunk documenta una decisione
--   :SIMILAR_TO   (Chunk)->(Chunk)         — similarita'' semantica sopra soglia
--   :DECIDED_BY   (Decision)->(Task)       — una decisione presa durante un task

SELECT create_graph('knowledge_graph');

-- ── Grafo 2: code_graph ─────────────────────────────────────────────────────
-- Grafo strutturale per relazioni nel codice sorgente.
-- Costruito automaticamente durante ingestion via analisi regex dei file Java.
--
-- Nodi:
--   :File      {path, language}            — file sorgente
--   :Class     {name, filePath, package}   — classe Java/Go/etc
--   :Interface {name, filePath, package}   — interfaccia
--   :Method    {name, className, params}   — metodo
--   :Package   {name}                      — package Java
--
-- Archi:
--   :IMPORTS    (Class)->(Class|Interface) — import esplicito
--   :EXTENDS    (Class)->(Class)          — ereditarieta''
--   :IMPLEMENTS (Class)->(Interface)      — implementazione interfaccia
--   :CALLS      (Method)->(Method)        — invocazione metodo
--   :CONTAINS   (File)->(Class|Interface) — file contiene classe
--   :DEFINED_IN (Class)->(Package)        — classe appartiene a package

SELECT create_graph('code_graph');
