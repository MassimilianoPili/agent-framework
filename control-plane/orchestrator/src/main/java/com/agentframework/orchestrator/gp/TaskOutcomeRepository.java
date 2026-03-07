package com.agentframework.orchestrator.gp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Repository for GP training data.
 *
 * <p>Uses native queries for pgvector column ({@code task_embedding vector(1024)})
 * because JPA cannot map {@code float[]} to the pgvector type natively.</p>
 */
public interface TaskOutcomeRepository extends JpaRepository<TaskOutcome, UUID> {

    /**
     * Inserts a task outcome with the pgvector embedding.
     * Uses native query to handle the {@code vector(1024)} cast.
     */
    @Modifying
    @Query(value = """
            INSERT INTO task_outcomes (id, plan_item_id, plan_id, task_key, worker_type,
                                       worker_profile, task_embedding, elo_at_dispatch,
                                       gp_mu, gp_sigma2, created_at)
            VALUES (:id, :planItemId, :planId, :taskKey, :workerType,
                    :workerProfile, cast(:embedding as vector), :eloAtDispatch,
                    :gpMu, :gpSigma2, now())
            """, nativeQuery = true)
    void insertWithEmbedding(@Param("id") UUID id,
                             @Param("planItemId") UUID planItemId,
                             @Param("planId") UUID planId,
                             @Param("taskKey") String taskKey,
                             @Param("workerType") String workerType,
                             @Param("workerProfile") String workerProfile,
                             @Param("embedding") String embedding,
                             @Param("eloAtDispatch") Double eloAtDispatch,
                             @Param("gpMu") Double gpMu,
                             @Param("gpSigma2") Double gpSigma2);

    /**
     * Loads training data for a specific worker type and profile.
     * Returns the last N outcomes with non-null reward and embedding.
     *
     * <p>The embedding is returned as a text representation {@code [0.1,0.2,...]}
     * which the service layer parses back to {@code float[]}.</p>
     */
    @Query(value = """
            SELECT id, plan_item_id, plan_id, task_key, worker_type, worker_profile,
                   task_embedding::text as embedding_text, elo_at_dispatch,
                   gp_mu, gp_sigma2, actual_reward, created_at
            FROM task_outcomes
            WHERE worker_type = :workerType
              AND worker_profile = :profile
              AND actual_reward IS NOT NULL
              AND task_embedding IS NOT NULL
            ORDER BY created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findTrainingDataRaw(@Param("workerType") String workerType,
                                       @Param("profile") String profile,
                                       @Param("limit") int limit);

    /**
     * Updates actual_reward when the task completes.
     */
    @Modifying
    @Query(value = """
            UPDATE task_outcomes SET actual_reward = :reward
            WHERE plan_item_id = :planItemId AND actual_reward IS NULL
            """, nativeQuery = true)
    int updateActualReward(@Param("planItemId") UUID planItemId,
                           @Param("reward") double reward);

    /**
     * Count outcomes per worker type/profile (for diagnostics).
     */
    @Query(value = """
            SELECT COUNT(*) FROM task_outcomes
            WHERE worker_type = :workerType
              AND worker_profile = :profile
              AND actual_reward IS NOT NULL
            """, nativeQuery = true)
    long countTrainingData(@Param("workerType") String workerType,
                           @Param("profile") String profile);

    /**
     * Finds the most similar task outcomes by cosine similarity (HNSW index).
     * Used for serendipity: find past tasks similar to a new task, then retrieve
     * their associated file outcomes.
     *
     * <p>Returns Object[] rows: [id(UUID), plan_id(UUID), task_key(String),
     * worker_type(String), worker_profile(String), gp_mu(Double),
     * actual_reward(Double), similarity(Double)].</p>
     */
    @Query(value = """
            SELECT id, plan_id, task_key, worker_type, worker_profile,
                   gp_mu, actual_reward,
                   1 - (task_embedding <=> cast(:embedding as vector)) as similarity
            FROM task_outcomes
            WHERE actual_reward IS NOT NULL
              AND gp_mu IS NOT NULL
              AND task_embedding IS NOT NULL
            ORDER BY task_embedding <=> cast(:embedding as vector)
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findSimilarOutcomes(@Param("embedding") String embedding,
                                        @Param("limit") int limit);

    /**
     * Returns all actual rewards grouped by worker_type (for portfolio covariance estimation).
     * Each row: [worker_type(String), actual_reward(Double)], ordered by worker_type then created_at.
     */
    @Query(value = """
            SELECT worker_type, actual_reward
            FROM task_outcomes
            WHERE actual_reward IS NOT NULL
            ORDER BY worker_type, created_at
            """, nativeQuery = true)
    List<Object[]> findRewardsByWorkerType();

    /**
     * Loads single outcome for a plan+taskKey (root cause target).
     */
    @Query(value = """
            SELECT elo_at_dispatch, gp_mu, gp_sigma2, actual_reward,
                   task_embedding::text as embedding_text, worker_type
            FROM task_outcomes
            WHERE plan_id = :planId AND task_key = :taskKey
            LIMIT 1
            """, nativeQuery = true)
    List<Object[]> findByPlanIdAndTaskKey(@Param("planId") UUID planId,
                                           @Param("taskKey") String taskKey);

    /**
     * Loads background population for a worker type (causal analysis).
     */
    @Query(value = """
            SELECT elo_at_dispatch, gp_mu, gp_sigma2, actual_reward,
                   task_embedding::text as embedding_text
            FROM task_outcomes
            WHERE worker_type = :workerType
              AND actual_reward IS NOT NULL
            ORDER BY created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findCausalDataByWorkerType(@Param("workerType") String workerType,
                                                @Param("limit") int limit);

    /**
     * Loads reward timeseries for a worker profile (drift detection).
     */
    @Query(value = """
            SELECT actual_reward, created_at
            FROM task_outcomes
            WHERE worker_profile = :profile
              AND actual_reward IS NOT NULL
            ORDER BY created_at ASC
            """, nativeQuery = true)
    List<Object[]> findRewardTimeseriesByProfile(@Param("profile") String profile);

    /**
     * Returns all distinct worker profiles that have recorded outcomes (for drift detection).
     */
    @Query(value = """
            SELECT DISTINCT worker_profile
            FROM task_outcomes
            WHERE actual_reward IS NOT NULL
            """, nativeQuery = true)
    List<String> findDistinctProfiles();

    /**
     * Loads GP predictions and actual outcomes for calibration audit.
     * Returns rows where both gp_mu and actual_reward are available.
     */
    @Query(value = """
            SELECT gp_mu, actual_reward, worker_type
            FROM task_outcomes
            WHERE gp_mu IS NOT NULL
              AND actual_reward IS NOT NULL
            ORDER BY created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findCalibrationData(@Param("limit") int limit);

    /**
     * Loads GP predictions and actual outcomes filtered by worker type (calibration audit).
     */
    @Query(value = """
            SELECT gp_mu, actual_reward
            FROM task_outcomes
            WHERE gp_mu IS NOT NULL
              AND actual_reward IS NOT NULL
              AND worker_type = :workerType
            ORDER BY created_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Object[]> findCalibrationDataByWorkerType(@Param("workerType") String workerType,
                                                    @Param("limit") int limit);

    /**
     * Loads all completed outcomes for a specific plan (Shapley credit attribution).
     * Returns rows: [worker_profile(String), actual_reward(Number), worker_type(String), task_key(String)].
     */
    @Query(value = """
            SELECT worker_profile, actual_reward, worker_type, task_key
            FROM task_outcomes
            WHERE plan_id = :planId
              AND actual_reward IS NOT NULL
            ORDER BY created_at ASC
            """, nativeQuery = true)
    List<Object[]> findOutcomesByPlanId(@Param("planId") UUID planId);

    /**
     * Finds the task outcome for a specific plan item (for serendipity collection).
     */
    @Query(value = """
            SELECT id, gp_mu, actual_reward
            FROM task_outcomes
            WHERE plan_item_id = :planItemId
              AND actual_reward IS NOT NULL
            LIMIT 1
            """, nativeQuery = true)
    List<Object[]> findOutcomeByPlanItemId(@Param("planItemId") UUID planItemId);
}
