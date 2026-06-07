package org.mockserver.state;

import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Filesystem-backed {@link BlobStore} implementation. Blob keys are
 * interpreted as file paths: absolute keys are used as-is; relative keys
 * are resolved against a configurable base directory.
 * <p>
 * The write path uses the same {@link FileOutputStream} + {@link FileChannel}
 * + {@link FileLock} pattern as the pre-existing
 * {@link org.mockserver.persistence.ExpectationFileSystemPersistence}, so
 * on-disk behaviour is byte-for-byte identical to today's persistence.
 * <p>
 * Metadata is stored alongside the data file as {@code <key>.meta} using
 * Java Properties format. When no metadata is provided, no {@code .meta}
 * file is written.
 * <p>
 * Thread-safety: a per-key {@link ReentrantLock} serialises concurrent
 * writes to the same key, matching the single-lock design of the original
 * persistence classes.
 */
public class FilesystemBlobStore implements BlobStore {

    private static final Logger FALLBACK_LOG = LoggerFactory.getLogger(FilesystemBlobStore.class);

    private final Path baseDir;
    private final MockServerLogger mockServerLogger;
    private final ConcurrentHashMap<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();

    /**
     * Creates a filesystem blob store with the given base directory.
     * The directory is created if it does not exist.
     *
     * @param baseDir         the base directory for relative keys
     * @param mockServerLogger logger for error reporting (may be null in tests)
     */
    public FilesystemBlobStore(Path baseDir, MockServerLogger mockServerLogger) {
        this.baseDir = baseDir;
        this.mockServerLogger = mockServerLogger;
    }

    /**
     * Creates a filesystem blob store using the current working directory
     * as the base directory.
     *
     * @param mockServerLogger logger for error reporting (may be null in tests)
     */
    public FilesystemBlobStore(MockServerLogger mockServerLogger) {
        this(Paths.get("."), mockServerLogger);
    }

    private Path resolvePath(String key) {
        Path keyPath = Paths.get(key);
        if (keyPath.isAbsolute()) {
            return keyPath;
        }
        return baseDir.resolve(keyPath);
    }

    private ReentrantLock lockForKey(String key) {
        return keyLocks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    @Override
    public void put(String key, byte[] data, Map<String, String> metadata) {
        Path filePath = resolvePath(key);
        ReentrantLock lock = lockForKey(key);
        lock.lock();
        try {
            // Ensure parent directories exist
            Path parent = filePath.getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    logError("exception creating directories for blob " + filePath, e);
                    throw new UncheckedIOException("failed to create directories for blob: " + key, e);
                }
            }

            // Ensure the file exists (matching ExpectationFileSystemPersistence pattern)
            try {
                Files.createFile(filePath);
            } catch (FileAlreadyExistsException ignore) {
                // expected
            } catch (IOException e) {
                logError("exception creating blob file " + filePath, e);
                throw new UncheckedIOException("failed to create blob file: " + key, e);
            }

            // Write data using FileOutputStream + FileChannel + FileLock
            // (identical pattern to ExpectationFileSystemPersistence.updated())
            try (
                FileOutputStream fileOutputStream = new FileOutputStream(filePath.toFile());
                FileChannel fileChannel = fileOutputStream.getChannel();
                FileLock fileLock = fileChannel.lock()
            ) {
                if (fileLock != null) {
                    ByteBuffer buffer = ByteBuffer.wrap(data);
                    buffer.put(data);
                    buffer.rewind();
                    while (buffer.hasRemaining()) {
                        fileChannel.write(buffer);
                    }
                }
            } catch (IOException e) {
                logError("exception while writing blob to " + filePath, e);
                throw new UncheckedIOException("failed to write blob: " + key, e);
            }

            // Write metadata file if metadata is non-empty
            if (metadata != null && !metadata.isEmpty()) {
                Path metaPath = Paths.get(filePath + ".meta");
                Properties props = new Properties();
                props.putAll(metadata);
                try (FileOutputStream metaOut = new FileOutputStream(metaPath.toFile())) {
                    props.store(metaOut, null);
                } catch (IOException e) {
                    logError("exception while writing blob metadata to " + metaPath, e);
                    throw new UncheckedIOException("failed to write blob metadata: " + key, e);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Optional<Blob> get(String key) {
        Path filePath = resolvePath(key);
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            byte[] data = Files.readAllBytes(filePath);
            Map<String, String> metadata = readMetadata(filePath);
            return Optional.of(new Blob(key, data, metadata));
        } catch (IOException e) {
            logError("exception while reading blob from " + filePath, e);
            throw new UncheckedIOException("failed to read blob: " + key, e);
        }
    }

    @Override
    public List<String> list(String prefix) {
        // The prefix may be a relative or absolute path prefix.
        // We resolve the prefix against baseDir to find the directory to scan,
        // then filter files whose keys start with the prefix.
        Path prefixPath = resolvePath(prefix);
        Path scanDir = prefixPath.getParent();
        if (scanDir == null) {
            scanDir = baseDir;
        }
        if (!Files.isDirectory(scanDir)) {
            return Collections.emptyList();
        }
        try (Stream<Path> stream = Files.walk(scanDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> !p.toString().endsWith(".meta"))
                .map(this::pathToKey)
                .filter(k -> k.startsWith(prefix))
                .collect(Collectors.toList());
        } catch (IOException e) {
            logError("exception while listing blobs with prefix " + prefix, e);
            throw new UncheckedIOException("failed to list blobs with prefix: " + prefix, e);
        }
    }

    @Override
    public boolean delete(String key) {
        Path filePath = resolvePath(key);
        ReentrantLock lock = lockForKey(key);
        lock.lock();
        try {
            boolean deleted = Files.deleteIfExists(filePath);
            // Also delete metadata file if present
            Path metaPath = Paths.get(filePath + ".meta");
            Files.deleteIfExists(metaPath);
            if (deleted) {
                keyLocks.remove(key);
            }
            return deleted;
        } catch (IOException e) {
            logError("exception while deleting blob " + filePath, e);
            throw new UncheckedIOException("failed to delete blob: " + key, e);
        } finally {
            lock.unlock();
        }
    }

    private String pathToKey(Path path) {
        Path keyPath = path.isAbsolute() ? path : baseDir.resolve(path);
        // If the path starts with baseDir, return relative; otherwise absolute
        if (keyPath.startsWith(baseDir)) {
            return baseDir.relativize(keyPath).toString();
        }
        return keyPath.toString();
    }

    private Map<String, String> readMetadata(Path filePath) {
        Path metaPath = Paths.get(filePath + ".meta");
        if (!Files.exists(metaPath)) {
            return Collections.emptyMap();
        }
        try (InputStream in = Files.newInputStream(metaPath)) {
            Properties props = new Properties();
            props.load(in);
            Map<String, String> result = new HashMap<>();
            for (String name : props.stringPropertyNames()) {
                result.put(name, props.getProperty(name));
            }
            return result;
        } catch (IOException e) {
            logError("exception while reading blob metadata from " + metaPath, e);
            throw new UncheckedIOException("failed to read blob metadata for: " + filePath, e);
        }
    }

    private void logError(String message, Throwable throwable) {
        if (mockServerLogger != null) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(message)
                    .setThrowable(throwable)
            );
        } else {
            FALLBACK_LOG.error(message, throwable);
        }
    }
}
