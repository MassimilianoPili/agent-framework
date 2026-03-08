package com.agentframework.worker.dto;

/**
 * G3: Lightweight record capturing a single file operation performed by a worker.
 * Accumulated in PolicyEnforcingToolCallback via ThreadLocal, then drained into AgentResult.
 */
public record FileModificationEvent(
    String filePath,
    String operation,          // CREATED, MODIFIED, DELETED
    String contentHashBefore,  // SHA-256 of file content before (null for CREATED)
    String contentHashAfter,   // SHA-256 of file content after (null for DELETED)
    String diffPreview         // first 500 chars of diff (nullable)
) {}
