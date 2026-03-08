package com.agentframework.orchestrator.repository;

import com.agentframework.orchestrator.domain.FileModification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FileModificationRepository extends JpaRepository<FileModification, Long> {

    List<FileModification> findByItemIdOrderByOccurredAtAsc(UUID itemId);

    List<FileModification> findByPlanIdOrderByOccurredAtAsc(UUID planId);
}
