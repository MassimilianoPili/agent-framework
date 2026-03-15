package com.agentframework.orchestrator.visualization;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST endpoints for the Architectural Visualization Generator.
 *
 * <p>Supports two output formats:</p>
 * <ul>
 *   <li><b>mermaid</b>: text/plain Mermaid diagram syntax</li>
 *   <li><b>d3json</b>: application/json D3.js-compatible graph data</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/visualization")
@ConditionalOnProperty(prefix = "visualization", name = "enabled", havingValue = "true", matchIfMissing = false)
public class VisualizationController {

    private final VisualizationService visualizationService;

    public VisualizationController(VisualizationService visualizationService) {
        this.visualizationService = visualizationService;
    }

    /**
     * Renders a single plan visualization.
     *
     * @param planId plan UUID
     * @param format output format: "mermaid" or "d3json" (default)
     */
    @GetMapping("/plans/{planId}")
    public ResponseEntity<Object> renderPlan(
            @PathVariable UUID planId,
            @RequestParam(defaultValue = "d3json") String format) {

        String result = visualizationService.renderPlan(planId, format);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return respondWithFormat(result, format);
    }

    /**
     * Renders a plan with its sub-plans (hierarchical view).
     *
     * @param planId root plan UUID
     * @param format output format: "mermaid" or "d3json" (default)
     */
    @GetMapping("/plans/{planId}/hierarchy")
    public ResponseEntity<Object> renderHierarchy(
            @PathVariable UUID planId,
            @RequestParam(defaultValue = "d3json") String format) {

        String result = visualizationService.renderHierarchy(planId, format);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return respondWithFormat(result, format);
    }

    /**
     * Renders a project-level visualization (all plans in a project).
     *
     * @param projectId project UUID
     * @param format    output format: "mermaid" or "d3json" (default)
     */
    @GetMapping("/projects/{projectId}")
    public ResponseEntity<Object> renderProject(
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "d3json") String format) {

        String result = visualizationService.renderProject(projectId, format);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return respondWithFormat(result, format);
    }

    private ResponseEntity<Object> respondWithFormat(String result, String format) {
        if ("mermaid".equalsIgnoreCase(format)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(result);
        }
        // D3 JSON — return the raw JSON string with proper content type
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(result);
    }
}
