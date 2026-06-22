package org.mockserver.state;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertTrue;

/**
 * Concurrency-focused tests for {@link FilesystemBlobStore#put} verifying the
 * atomic-write fix: writes go to a temp file in the same directory and are then
 * atomically moved into place, so a concurrent reader that holds no lock (the
 * {@code FileWatcher} poll or {@link FilesystemBlobStore#get}) never observes a
 * truncated / partially-written / empty target file mid-write.
 *
 * <p>Before the fix, {@code put} opened a {@code FileOutputStream} directly on
 * the target which truncates it on open, BEFORE acquiring the {@code FileLock}
 * and writing — exposing an empty-file window to lock-free readers.</p>
 */
public class FilesystemBlobStoreAtomicWriteTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private FilesystemBlobStore store;
    private Path baseDir;

    @Before
    public void setUp() {
        baseDir = tempFolder.getRoot().toPath();
        store = new FilesystemBlobStore(baseDir, null);
    }

    @Test
    public void concurrentReaderNeverObservesTruncatedOrPartialFile() throws Exception {
        final String key = "concurrent.json";
        final Path target = baseDir.resolve(key);

        // Two distinct, fully-valid payloads of different lengths. A reader using
        // raw byte reads (like the FileWatcher) must always observe ONE of these
        // exact payloads in full, never an empty file and never a partial blend.
        final byte[] payloadA = repeat('A', 64 * 1024);
        final byte[] payloadB = repeat('B', 96 * 1024);

        // Seed with a complete payload so the file is valid from the very first read.
        store.put(key, payloadA, Collections.emptyMap());

        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<String> badReads = new CopyOnWriteArrayList<>();
        final CountDownLatch readerStarted = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            readerStarted.countDown();
            while (!stop.get()) {
                try {
                    byte[] read = Files.readAllBytes(target);
                    if (!isCompletePayload(read, payloadA, payloadB)) {
                        badReads.add("observed length " + read.length
                            + " which is neither payloadA(" + payloadA.length + ") nor payloadB(" + payloadB.length + ")");
                    }
                } catch (IOException ioe) {
                    // The atomic-move approach keeps the target present at all times
                    // once seeded; a missing-file read would itself be a defect.
                    badReads.add("read failed: " + ioe.getMessage());
                }
            }
        }, "blob-reader");
        reader.setDaemon(true);
        reader.start();
        readerStarted.await();

        // Hammer the file with alternating full writes while the reader observes it.
        for (int i = 0; i < 400; i++) {
            store.put(key, (i % 2 == 0) ? payloadB : payloadA, Collections.emptyMap());
        }

        stop.set(true);
        reader.join(10_000);

        assertThat("reader observed truncated/partial/empty/missing content during writes: " + badReads,
            badReads, is(empty()));
    }

    @Test
    public void putThenGetReturnsCompletePayload() {
        byte[] data = repeat('Z', 200 * 1024);
        store.put("complete.json", data, Collections.emptyMap());

        Optional<Blob> result = store.get("complete.json");
        assertTrue(result.isPresent());
        assertThat(result.get().getData().length, is(data.length));
        assertTrue("returned bytes must equal the written bytes", Arrays.equals(data, result.get().getData()));
    }

    @Test
    public void putDoesNotLeaveTemporaryFilesBehind() throws Exception {
        byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
        store.put("clean.json", data, Collections.emptyMap());
        store.put("clean.json", "payload-2".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());

        try (Stream<Path> files = Files.list(baseDir)) {
            List<String> leftoverTempFiles = files
                .map(p -> p.getFileName().toString())
                .filter(name -> name.endsWith(".tmp"))
                .collect(java.util.stream.Collectors.toList());
            assertThat("the atomic write must not leave .tmp files behind", leftoverTempFiles, is(empty()));
        }
    }

    @Test
    public void overwriteReplacesPreviousContentAtomically() {
        store.put("ov.json", "first".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
        store.put("ov.json", "second-longer".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());

        Optional<Blob> result = store.get("ov.json");
        assertTrue(result.isPresent());
        assertThat(new String(result.get().getData(), StandardCharsets.UTF_8), is("second-longer"));
    }

    private static boolean isCompletePayload(byte[] read, byte[] a, byte[] b) {
        return Arrays.equals(read, a) || Arrays.equals(read, b);
    }

    private static byte[] repeat(char c, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) c);
        return bytes;
    }
}
