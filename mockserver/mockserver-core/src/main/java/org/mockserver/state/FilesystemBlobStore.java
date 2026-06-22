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

            // Atomic write: write the full payload to a temporary file in the
            // SAME directory, then atomically move it into place. This means a
            // concurrent reader (e.g. the FileWatcher poll, or BlobStore.get())
            // never observes a truncated/partial file mid-write — it sees either
            // the previous complete file or the new complete file. The previous
            // implementation opened a FileOutputStream directly on the target,
            // which truncates the file on open BEFORE the FileLock is acquired
            // and the data written, exposing an empty-file window to readers
            // that hold no lock (Files.readAllBytes takes no FileLock).
            Path tempPath;
            try {
                // Place the temp file in the SAME directory as the target so the
                // subsequent move is a same-filesystem rename (a prerequisite for
                // ATOMIC_MOVE). A null parent only arises for a filesystem-root
                // key, which is never a valid blob key; fall back to the base dir.
                Path tempDir = filePath.getParent() != null ? filePath.getParent() : baseDir;
                tempPath = Files.createTempFile(tempDir, filePath.getFileName().toString(), ".tmp");
            } catch (IOException e) {
                logError("exception creating temporary blob file for " + filePath, e);
                throw new UncheckedIOException("failed to create temporary blob file: " + key, e);
            }

            try {
                // Write the full payload to the temp file, holding a FileLock for
                // the duration to serialise against other writers/processes.
                try (
                    FileOutputStream fileOutputStream = new FileOutputStream(tempPath.toFile());
                    FileChannel fileChannel = fileOutputStream.getChannel();
                    FileLock fileLock = fileChannel.lock()
                ) {
                    if (fileLock != null) {
                        ByteBuffer buffer = ByteBuffer.wrap(data);
                        while (buffer.hasRemaining()) {
                            fileChannel.write(buffer);
                        }
                        fileChannel.force(true);
                    }
                } catch (IOException e) {
                    logError("exception while writing blob to " + tempPath, e);
                    throw new UncheckedIOException("failed to write blob: " + key, e);
                }

                // Atomically move the completed temp file into place. Fall back to
                // a plain replace if the filesystem does not support atomic moves.
                try {
                    Files.move(tempPath, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException atomicNotSupported) {
                    try {
                        Files.move(tempPath, filePath, StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        logError("exception while moving blob into place at " + filePath, e);
                        throw new UncheckedIOException("failed to write blob: " + key, e);
                    }
                } catch (IOException e) {
                    logError("exception while moving blob into place at " + filePath, e);
                    throw new UncheckedIOException("failed to write blob: " + key, e);
                }
            } finally {
                // If the move succeeded the temp file is gone; this is a no-op.
                // If it failed we clean up the orphaned temp file.
                try {
                    Files.deleteIfExists(tempPath);
                } catch (IOException ignore) {
                    // best-effort cleanup
                }
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
