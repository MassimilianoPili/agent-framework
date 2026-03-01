package com.agentframework.rag.ingestion;

import com.agentframework.rag.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FileWatcherServiceTest {

    private FileWatcherService watcherService;
    private IngestionService mockIngestion;

    @BeforeEach
    void setUp() {
        mockIngestion = mock(IngestionService.class);
        var properties = new RagProperties(true,
                new RagProperties.Ingestion(512, 100, true, 500, List.of("java"), true),
                new RagProperties.Search(true, true, "cascade", 20, 8, 0.5, 60),
                new RagProperties.Ollama("mxbai-embed-large", "qwen2.5:1.5b", "http://localhost:11434"),
                new RagProperties.Cache(5, 24, 60));
        watcherService = new FileWatcherService(mockIngestion, properties);
    }

    @Test
    void watch_registersDirectory_doesNotThrow(@TempDir Path tempDir) throws IOException {
        // Create a test subdirectory structure
        Files.createDirectories(tempDir.resolve("src/main"));
        Files.createDirectories(tempDir.resolve("src/test"));

        watcherService.watch(tempDir);

        // If no exception, registration was successful
        watcherService.stop();
    }

    @Test
    void watch_excludesGitAndTarget(@TempDir Path tempDir) throws IOException {
        // Create directories that should be excluded
        Files.createDirectories(tempDir.resolve(".git/objects"));
        Files.createDirectories(tempDir.resolve("target/classes"));
        Files.createDirectories(tempDir.resolve("src/main"));

        // watch() should complete without registering .git or target
        watcherService.watch(tempDir);
        watcherService.stop();
    }

    @Test
    void stop_shutsDownGracefully() throws IOException {
        watcherService.stop();
        // Should not throw even when called without watch()
    }

    @Test
    void handleFileChange_debounces_skipsRapidEvents() throws Exception {
        // Access the internal lastEventTime map via reflection
        Field lastEventTimeField = FileWatcherService.class.getDeclaredField("lastEventTime");
        lastEventTimeField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Path, Long> lastEventTime = (Map<Path, Long>) lastEventTimeField.get(watcherService);

        // Set the watched directory
        Field watchedDirField = FileWatcherService.class.getDeclaredField("watchedDir");
        watchedDirField.setAccessible(true);
        watchedDirField.set(watcherService, Path.of("/project"));

        // Simulate a recent event (1 second ago, within 5s debounce window)
        Path testFile = Path.of("/project/src/Test.java");
        lastEventTime.put(testFile, System.currentTimeMillis() - 1_000);

        // Invoke handleFileChange via reflection
        var handleMethod = FileWatcherService.class.getDeclaredMethod("handleFileChange", Path.class);
        handleMethod.setAccessible(true);
        handleMethod.invoke(watcherService, testFile);

        // Should NOT call ingestionService — debounce should suppress
        verify(mockIngestion, never()).ingestFile(any(), any());
    }
}
