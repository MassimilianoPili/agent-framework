package com.agentframework.rag.ingestion;

import com.agentframework.rag.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Watches the codebase for file changes and triggers incremental re-ingestion.
 *
 * <p>Uses Java NIO WatchService (inotify on Linux). Changes are debounced by 5 seconds
 * to avoid re-indexing during rapid edits (e.g. auto-save).</p>
 *
 * <p>Disabled by default — enable with {@code rag.ingestion.file-watcher-enabled=true}.</p>
 */
@Service
@ConditionalOnProperty(prefix = "rag.ingestion", name = "file-watcher-enabled", havingValue = "true")
public class FileWatcherService {

    private static final Logger log = LoggerFactory.getLogger(FileWatcherService.class);

    private static final long DEBOUNCE_MS = 5_000;

    private final IngestionService ingestionService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rag-file-watcher");
        t.setDaemon(true);
        return t;
    });

    private final Map<Path, Long> lastEventTime = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private WatchService watchService;
    private Path watchedDir;

    public FileWatcherService(IngestionService ingestionService, RagProperties properties) {
        this.ingestionService = ingestionService;
    }

    /**
     * Start watching a directory for file changes.
     */
    public void watch(Path directory) throws IOException {
        this.watchedDir = directory;
        this.watchService = FileSystems.getDefault().newWatchService();

        // Register all subdirectories
        Files.walk(directory)
                .filter(Files::isDirectory)
                .filter(d -> !d.toString().contains(".git") && !d.toString().contains("target"))
                .forEach(dir -> {
                    try {
                        dir.register(watchService,
                                StandardWatchEventKinds.ENTRY_CREATE,
                                StandardWatchEventKinds.ENTRY_MODIFY);
                    } catch (IOException e) {
                        log.warn("[RAG Watcher] Failed to register {}: {}", dir, e.getMessage());
                    }
                });

        executor.submit(this::pollLoop);
        log.info("[RAG Watcher] Started watching {}", directory);
    }

    private void pollLoop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

                    @SuppressWarnings("unchecked")
                    Path changed = ((WatchKey) key).watchable() instanceof Path parent
                            ? parent.resolve((Path) event.context())
                            : (Path) event.context();

                    handleFileChange(changed);
                }
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void handleFileChange(Path file) {
        long now = System.currentTimeMillis();
        Long lastTime = lastEventTime.get(file);

        if (lastTime != null && (now - lastTime) < DEBOUNCE_MS) {
            return; // Debounce — skip rapid successive events
        }
        lastEventTime.put(file, now);

        if (Files.isRegularFile(file)) {
            log.debug("[RAG Watcher] File changed: {}", file);
            ingestionService.ingestFile(file, watchedDir);
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        executor.shutdownNow();
        try {
            if (watchService != null) watchService.close();
        } catch (IOException e) {
            log.warn("[RAG Watcher] Error closing watch service: {}", e.getMessage());
        }
        log.info("[RAG Watcher] Stopped");
    }
}
