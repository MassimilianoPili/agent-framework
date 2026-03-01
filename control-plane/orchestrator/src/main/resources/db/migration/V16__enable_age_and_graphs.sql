-- V16: Apache AGE graph extension + knowledge_graph + code_graph
-- Richiede immagine PostgreSQL custom con AGE preinstallato (sol/postgres:pg16-age)

-- Attiva l'estensione AGE (compilata nel Dockerfile custom)
CREATE EXTENSION IF NOT EXISTS age;

-- Carica le funzioni AGE nella sessione e imposta il search_path
LOAD 'age';
SET search_path = ag_catalog, "$user", public;

-- Grafo 1: knowledge_graph — chunk, concetti, decisioni, relazioni semantiche
SELECT create_graph('knowledge_graph');

-- Grafo 2: code_graph — file, classi, metodi, package, dipendenze strutturali
SELECT create_graph('code_graph');
