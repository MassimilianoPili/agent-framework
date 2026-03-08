package com.agentframework.orchestrator.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * G3: Tracks every file written/created/deleted by workers during task execution.
 * Populated from worker-reported {@code FileModificationEvent}s via the result stream.
 */
@Entity
@Table(name = "file_modifications")
public class FileModification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "task_key", nullable = false)
    private String taskKey;

    @Column(name = "file_path", nullable = false, length = 1024)
    private String filePath;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private FileOperation operation;

    @Column(name = "content_hash_before", length = 64)
    private String contentHashBefore;

    @Column(name = "content_hash_after", length = 64)
    private String contentHashAfter;

    @Column(name = "diff_preview", columnDefinition = "TEXT")
    private String diffPreview;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected FileModification() {}

    public FileModification(UUID planId, UUID itemId, String taskKey,
                            String filePath, FileOperation operation,
                            String contentHashBefore, String contentHashAfter,
                            String diffPreview) {
        this.planId = planId;
        this.itemId = itemId;
        this.taskKey = taskKey;
        this.filePath = filePath;
        this.operation = operation;
        this.contentHashBefore = contentHashBefore;
        this.contentHashAfter = contentHashAfter;
        this.diffPreview = diffPreview;
        this.occurredAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getPlanId() { return planId; }
    public UUID getItemId() { return itemId; }
    public String getTaskKey() { return taskKey; }
    public String getFilePath() { return filePath; }
    public FileOperation getOperation() { return operation; }
    public String getContentHashBefore() { return contentHashBefore; }
    public String getContentHashAfter() { return contentHashAfter; }
    public String getDiffPreview() { return diffPreview; }
    public Instant getOccurredAt() { return occurredAt; }
}
