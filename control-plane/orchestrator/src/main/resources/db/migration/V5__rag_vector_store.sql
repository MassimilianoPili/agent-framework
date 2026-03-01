-- ============================================================================
-- V5: RAG Vector Store
-- Estensione pgvector + tabella vector_store con ricerca ibrida
-- (vettoriale coseno + full-text BM25). Trigger auto-update tsvector.
-- Richiede immagine PostgreSQL: pgvector/pgvector:pg16 (o sol/postgres:pg16-age)
-- ============================================================================

CREATE EXTENSION IF NOT EXISTS vector;

-- ── Tabella vector_store ────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS vector_store (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content       TEXT NOT NULL,
    metadata      JSONB DEFAULT '{}',
    embedding     vector(1024),
    search_vector tsvector
);

COMMENT ON TABLE vector_store IS 'RAG: documenti indicizzati con embedding vettoriali e full-text search';
COMMENT ON COLUMN vector_store.id IS 'UUID auto-generato per ogni chunk indicizzato';
COMMENT ON COLUMN vector_store.content IS 'Testo del chunk (con context prefix dall''enrichment pipeline)';
COMMENT ON COLUMN vector_store.metadata IS 'JSONB: filePath, language, docType, entities, keyphrases, chunkIndex';
COMMENT ON COLUMN vector_store.embedding IS 'Vettore mxbai-embed-large (1024 dimensioni) per similarita'' coseno via HNSW';
COMMENT ON COLUMN vector_store.search_vector IS 'tsvector auto-generato dal trigger per BM25 full-text search (ts_rank)';

-- ── Indici ──────────────────────────────────────────────────────────────────

-- HNSW: approximate nearest neighbor (cosine distance)
CREATE INDEX idx_vector_store_embedding ON vector_store
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 200);

-- GIN: filtri JSONB (language, filePath, docType)
CREATE INDEX idx_vector_store_metadata ON vector_store
    USING gin (metadata jsonb_path_ops);

-- GIN: full-text search (BM25-like ranking via ts_rank)
CREATE INDEX idx_vector_store_fts ON vector_store
    USING gin (search_vector);

-- ── Trigger: auto-update tsvector su INSERT/UPDATE ──────────────────────────

CREATE OR REPLACE FUNCTION update_search_vector() RETURNS trigger AS $$
BEGIN
    NEW.search_vector := to_tsvector('english', NEW.content);
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

COMMENT ON FUNCTION update_search_vector() IS 'Trigger function: rigenera tsvector dal content per BM25 search';

CREATE TRIGGER trg_update_search_vector
    BEFORE INSERT OR UPDATE OF content ON vector_store
    FOR EACH ROW EXECUTE FUNCTION update_search_vector();
