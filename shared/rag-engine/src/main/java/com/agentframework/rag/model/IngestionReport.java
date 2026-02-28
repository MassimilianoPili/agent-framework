package com.agentframework.rag.model;

import java.time.Duration;
import java.util.List;

/**
 * Report of an ingestion pipeline execution.
 *
 * @param filesProcessed number of files read
 * @param chunksCreated  number of chunks stored in vector store
 * @param duration       total pipeline duration
 * @param errors         list of errors encountered (file path + message)
 */
public record IngestionReport(
        int filesProcessed,
        int chunksCreated,
        Duration duration,
        List<String> errors
) {
    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }
}
