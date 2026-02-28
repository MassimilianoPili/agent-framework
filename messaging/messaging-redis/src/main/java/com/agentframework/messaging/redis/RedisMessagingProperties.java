package com.agentframework.messaging.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Redis Streams messaging.
 */
@ConfigurationProperties(prefix = "messaging.redis")
public class RedisMessagingProperties {

    /** Redis host. */
    private String host = "localhost";

    /** Redis port. */
    private int port = 6379;

    /** Redis database index for messaging streams. */
    private int database = 3;

    /** Redis database index for caching. */
    private int cacheDatabase = 4;

    /** Poll timeout in milliseconds for XREAD blocking. */
    private long pollTimeoutMs = 2000;

    /** Batch size for XREAD (max messages per poll). */
    private int batchSize = 10;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public int getDatabase() { return database; }
    public void setDatabase(int database) { this.database = database; }

    public int getCacheDatabase() { return cacheDatabase; }
    public void setCacheDatabase(int cacheDatabase) { this.cacheDatabase = cacheDatabase; }

    public long getPollTimeoutMs() { return pollTimeoutMs; }
    public void setPollTimeoutMs(long pollTimeoutMs) { this.pollTimeoutMs = pollTimeoutMs; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
}
