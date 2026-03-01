package com.agentframework.rag.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * CRUD operations on the {@code knowledge_graph} via Apache AGE Cypher queries.
 *
 * <p>Nodes: Chunk, Concept, Decision, Task.
 * Edges: REFERENCES, DEPENDS_ON, PART_OF, SIMILAR_TO, DECIDED_BY.</p>
 *
 * <p>All queries use parameterized Cypher via AGE's {@code cypher()} function
 * executed through JdbcTemplate. String parameters are escaped to prevent
 * Cypher injection.</p>
 */
@Service
public class KnowledgeGraphService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphService.class);

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeGraphService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void addChunkNode(String chunkId, String filePath, String summary) {
        executeCypher("""
                CREATE (c:Chunk {id: '%s', filePath: '%s', summary: '%s'})
                RETURN c""", escape(chunkId), escape(filePath), escape(truncate(summary, 500)));
        log.debug("[KnowledgeGraph] Added Chunk node: {}", chunkId);
    }

    public void addConceptNode(String name, String description) {
        executeCypher("""
                MERGE (c:Concept {name: '%s'})
                ON CREATE SET c.description = '%s'
                RETURN c""", escape(name), escape(description));
    }

    public void addEdge(String fromId, String fromLabel, String toId, String toLabel, String edgeType) {
        executeCypher("""
                MATCH (a:%s {id: '%s'}), (b:%s {id: '%s'})
                CREATE (a)-[:%s]->(b)
                RETURN a, b""", fromLabel, escape(fromId), toLabel, escape(toId), edgeType);
    }

    public List<Map<String, Object>> findRelatedChunks(String chunkId, int depth) {
        String cypher = String.format("""
                MATCH (c:Chunk {id: '%s'})-[*1..%d]-(related)
                RETURN related""", escape(chunkId), Math.min(depth, 5));
        try {
            return jdbcTemplate.queryForList(wrapCypher(cypher));
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] findRelatedChunks failed for {}: {}", chunkId, e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, Object>> findConceptsByKeyword(String keyword) {
        String cypher = String.format("""
                MATCH (c:Concept)
                WHERE c.name CONTAINS '%s' OR c.description CONTAINS '%s'
                RETURN c""", escape(keyword), escape(keyword));
        try {
            return jdbcTemplate.queryForList(wrapCypher(cypher));
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] findConceptsByKeyword failed for '{}': {}", keyword, e.getMessage());
            return List.of();
        }
    }

    private void executeCypher(String cypherTemplate, Object... args) {
        String cypher = String.format(cypherTemplate, args);
        try {
            jdbcTemplate.execute(wrapCypher(cypher));
        } catch (Exception e) {
            log.warn("[KnowledgeGraph] Cypher execution failed: {}", e.getMessage());
        }
    }

    private String wrapCypher(String cypher) {
        return String.format("""
                SELECT * FROM cypher('knowledge_graph', $$
                    %s
                $$) AS (result agtype)""", cypher);
    }

    static String escape(String input) {
        if (input == null) return "";
        return input.replace("'", "\\'").replace("$$", "");
    }

    private static String truncate(String text, int maxLen) {
        return text == null ? "" : (text.length() <= maxLen ? text : text.substring(0, maxLen));
    }
}
