package com.agentframework.rag.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CRUD operations on the {@code code_graph} via Apache AGE Cypher queries.
 *
 * <p>Nodes: File, Class, Interface, Method, Package.
 * Edges: IMPORTS, EXTENDS, IMPLEMENTS, CALLS, CONTAINS, DEFINED_IN.</p>
 *
 * <p>Supports automatic extraction of structural relationships from Java source
 * via regex-based parsing (lightweight, no full AST required).</p>
 */
@Service
public class CodeGraphService {

    private static final Logger log = LoggerFactory.getLogger(CodeGraphService.class);

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern CLASS_PATTERN = Pattern.compile("(?:public\\s+)?(?:abstract\\s+)?class\\s+(\\w+)(?:\\s+extends\\s+(\\w+))?(?:\\s+implements\\s+([\\w,\\s]+))?");
    private static final Pattern INTERFACE_PATTERN = Pattern.compile("(?:public\\s+)?interface\\s+(\\w+)(?:\\s+extends\\s+([\\w,\\s]+))?");
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);

    private final JdbcTemplate jdbcTemplate;

    public CodeGraphService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void addClassNode(String className, String filePath, String packageName) {
        executeCypher("""
                MERGE (c:Class {name: '%s'})
                ON CREATE SET c.filePath = '%s', c.package = '%s'
                RETURN c""", escape(className), escape(filePath), escape(packageName));
    }

    public void addImportEdge(String fromClass, String importedFqn) {
        executeCypher("""
                MATCH (c:Class {name: '%s'})
                MERGE (i:Class {name: '%s'})
                CREATE (c)-[:IMPORTS]->(i)
                RETURN c, i""", escape(fromClass), escape(shortName(importedFqn)));
    }

    public void addExtendsEdge(String childClass, String parentClass) {
        executeCypher("""
                MATCH (c:Class {name: '%s'})
                MERGE (p:Class {name: '%s'})
                CREATE (c)-[:EXTENDS]->(p)
                RETURN c, p""", escape(childClass), escape(parentClass));
    }

    public void addImplementsEdge(String className, String interfaceName) {
        executeCypher("""
                MATCH (c:Class {name: '%s'})
                MERGE (i:Interface {name: '%s'})
                CREATE (c)-[:IMPLEMENTS]->(i)
                RETURN c, i""", escape(className), escape(interfaceName));
    }

    public List<Map<String, Object>> findClassesByName(String name) {
        String cypher = String.format("""
                MATCH (c)
                WHERE (c:Class OR c:Interface) AND c.name CONTAINS '%s'
                RETURN c""", escape(name));
        try {
            return jdbcTemplate.queryForList(wrapCypher(cypher));
        } catch (Exception e) {
            log.warn("[CodeGraph] findClassesByName failed for '{}': {}", name, e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, Object>> findDependencies(String className, int depth) {
        String cypher = String.format("""
                MATCH (c:Class {name: '%s'})-[:IMPORTS|EXTENDS|IMPLEMENTS*1..%d]->(dep)
                RETURN dep""", escape(className), Math.min(depth, 5));
        try {
            return jdbcTemplate.queryForList(wrapCypher(cypher));
        } catch (Exception e) {
            log.warn("[CodeGraph] findDependencies failed for {}: {}", className, e.getMessage());
            return List.of();
        }
    }

    /**
     * Extract structural information from Java source code and populate the code graph.
     *
     * @param javaSource the Java source code
     * @param filePath   the file path (for File node)
     */
    public void extractAndPopulate(String javaSource, String filePath) {
        if (javaSource == null || javaSource.isBlank()) return;

        String packageName = extractPackage(javaSource);

        // Extract classes
        Matcher classMatcher = CLASS_PATTERN.matcher(javaSource);
        while (classMatcher.find()) {
            String className = classMatcher.group(1);
            addClassNode(className, filePath, packageName);

            // extends
            if (classMatcher.group(2) != null) {
                addExtendsEdge(className, classMatcher.group(2).strip());
            }
            // implements
            if (classMatcher.group(3) != null) {
                for (String iface : classMatcher.group(3).split(",")) {
                    addImplementsEdge(className, iface.strip());
                }
            }
        }

        // Extract interfaces
        Matcher ifaceMatcher = INTERFACE_PATTERN.matcher(javaSource);
        while (ifaceMatcher.find()) {
            String ifaceName = ifaceMatcher.group(1);
            executeCypher("""
                    MERGE (i:Interface {name: '%s'})
                    ON CREATE SET i.filePath = '%s', i.package = '%s'
                    RETURN i""", escape(ifaceName), escape(filePath), escape(packageName));
        }

        // Extract imports
        Matcher importMatcher = IMPORT_PATTERN.matcher(javaSource);
        List<String> classes = extractClassNames(javaSource);
        while (importMatcher.find()) {
            String importFqn = importMatcher.group(1);
            for (String className : classes) {
                addImportEdge(className, importFqn);
            }
        }

        log.debug("[CodeGraph] Extracted structure from {}", filePath);
    }

    static String extractPackage(String source) {
        Matcher m = PACKAGE_PATTERN.matcher(source);
        return m.find() ? m.group(1) : "";
    }

    static List<String> extractClassNames(String source) {
        List<String> names = new ArrayList<>();
        Matcher cm = CLASS_PATTERN.matcher(source);
        while (cm.find()) names.add(cm.group(1));
        Matcher im = INTERFACE_PATTERN.matcher(source);
        while (im.find()) names.add(im.group(1));
        return names;
    }

    private void executeCypher(String cypherTemplate, Object... args) {
        String cypher = String.format(cypherTemplate, args);
        try {
            jdbcTemplate.execute(wrapCypher(cypher));
        } catch (Exception e) {
            log.warn("[CodeGraph] Cypher execution failed: {}", e.getMessage());
        }
    }

    private String wrapCypher(String cypher) {
        return String.format("""
                SELECT * FROM cypher('code_graph', $$
                    %s
                $$) AS (result agtype)""", cypher);
    }

    static String escape(String input) {
        if (input == null) return "";
        return input.replace("'", "\\'").replace("$$", "");
    }

    private static String shortName(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
    }
}
