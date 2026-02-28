-- V15: Enable pgvector extension and create vector_store table for RAG pipeline.
-- Requires PostgreSQL image: pgvector/pgvector:pg16

CREATE EXTENSION IF NOT EXISTS vector;

-- Vector store table — single table with hybrid search (vector + BM25)
CREATE TABLE IF NOT EXISTS vector_store (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    content       TEXT NOT NULL,
    metadata      JSONB DEFAULT '{}',
    embedding     vector(1024),        -- mxbai-embed-large: 1024 dimensions
    search_vector tsvector
);

-- HNSW index for approximate nearest neighbor (cosine distance)
CREATE INDEX idx_vector_store_embedding ON vector_store
    USING hnsw (embedding vector_cosine_ops) WITH (m = 16, ef_construction = 200);

-- GIN index on JSONB metadata for filtered queries (e.g. by language, filePath)
CREATE INDEX idx_vector_store_metadata ON vector_store USING gin (metadata jsonb_path_ops);

-- GIN index on tsvector for full-text search (BM25-like ranking via ts_rank)
CREATE INDEX idx_vector_store_fts ON vector_store USING gin (search_vector);

-- Trigger: auto-update tsvector on INSERT or UPDATE of content
CREATE OR REPLACE FUNCTION update_search_vector() RETURNS trigger AS $$
BEGIN
    NEW.search_vector := to_tsvector('english', NEW.content);
    RETURN NEW;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_update_search_vector
    BEFORE INSERT OR UPDATE OF content ON vector_store
    FOR EACH ROW EXECUTE FUNCTION update_search_vector();
