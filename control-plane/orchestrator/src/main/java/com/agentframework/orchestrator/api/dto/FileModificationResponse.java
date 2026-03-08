package com.agentframework.orchestrator.api.dto;

import com.agentframework.orchestrator.domain.FileModification;

import java.time.Instant;
import java.util.UUID;

public record FileModificationResponse(
    Long id,
    UUID planId,
    UUID itemId,
    String taskKey,
    String filePath,
    String operation,
    String contentHashBefore,
    String contentHashAfter,
    String diffPreview,
    Instant occurredAt
) {
    public static FileModificationResponse from(FileModification fm) {
        return new FileModificationResponse(
            fm.getId(),
            fm.getPlanId(),
            fm.getItemId(),
            fm.getTaskKey(),
            fm.getFilePath(),
            fm.getOperation().name(),
            fm.getContentHashBefore(),
            fm.getContentHashAfter(),
            fm.getDiffPreview(),
            fm.getOccurredAt()
        );
    }
}
