package com.agentframework.orchestrator.reward;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkerEloStatsRepository extends JpaRepository<WorkerEloStats, String> {

    /** Returns all profiles ordered by ELO rating descending (leaderboard). */
    List<WorkerEloStats> findAllByOrderByEloRatingDesc();
}
