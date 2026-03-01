package com.agentframework.workers.ragmanager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Boot application for the RAG Manager Worker.
 *
 * <p>Programmatic worker that retrieves semantic context from the RAG pipeline
 * (pgvector hybrid search + Apache AGE graph queries) and publishes it as
 * dependency results for downstream domain workers.</p>
 */
@SpringBootApplication
public class RagManagerWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagManagerWorkerApplication.class, args);
    }
}
