package org.mockserver.state;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * Tests for {@link FilesystemBlobStore}. Verifies that blobs are correctly
 * written to and read from the filesystem, that the on-disk format matches
 * the byte-for-byte output of the pre-existing persistence classes, and
 * that the BlobStore SPI contract is honoured.
 */
public class FilesystemBlobStoreTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private FilesystemBlobStore store;
    private Path baseDir;

    @Before
    public void setUp() {
        baseDir = tempFolder.getRoot().toPath();
        store = new FilesystemBlobStore(baseDir, null);
    }

    // --- put / get round-trip ---

    @Test
    public void shouldPutAndGetWithRelativeKey() {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        store.put("test.json", data, Collections.emptyMap());

        Optional<Blob> result = store.get("test.json");
        assertTrue("blob should be present", result.isPresent());
        assertThat(result.get().getKey(), is("test.json"));
        assertThat(new String(result.get().getData(), StandardCharsets.UTF_8), is("hello world"));

        // Verify the file exists on disk at the expected location
        Path expectedFile = baseDir.resolve("test.json");
        assertTrue("file should exist on disk", Files.exists(expectedFile));
    }

    @Test
    public void shouldPutAndGetWithAbsoluteKey() throws Exception {
        File tempFile = tempFolder.newFile("absolute.json");
        String absoluteKey = tempFile.getAbsolutePath();
        byte[] data = "{\"key\": \"value\"}".getBytes(StandardCharsets.UTF_8);

        store.put(absoluteKey, data, Collections.emptyMap());

        Optional<Blob> result = store.get(absoluteKey);
        assertTrue("blob should be present", result.isPresent());
        assertThat(new String(result.get().getData(), StandardCharsets.UTF_8), is("{\"key\": \"value\"}"));

        // Verify file on disk
        byte[] diskContent = Files.readAllBytes(tempFile.toPath());
        assertThat("on-disk content should be byte-for-byte identical",
            new String(diskContent, StandardCharsets.UTF_8), is("{\"key\": \"value\"}"));
    }

    @Test
    public void shouldOverwriteExistingBlob() {
        store.put("overwrite.json", "v1".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
        store.put("overwrite.json", "v2".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());

        Optional<Blob> result = store.get("overwrite.json");
        assertTrue(result.isPresent());
        assertThat(new String(result.get().getData(), StandardCharsets.UTF_8), is("v2"));
    }

    @Test
    public void shouldCreateParentDirectories() {
        byte[] data = "nested".getBytes(StandardCharsets.UTF_8);
        store.put("a/b/c/nested.json", data, Collections.emptyMap());

        assertTrue("nested file should exist", Files.exists(baseDir.resolve("a/b/c/nested.json")));
        Optional<Blob> result = store.get("a/b/c/nested.json");
        assertTrue(result.isPresent());
        assertThat(new String(result.get().getData(), StandardCharsets.UTF_8), is("nested"));
    }

    // --- get (missing key) ---

    @Test
    public void shouldReturnEmptyForMissingKey() {
        assertFalse(store.get("nonexistent.json").isPresent());
    }

    // --- metadata ---

    @Test
    public void shouldPersistAndReadMetadata() {
        Map<String, String> meta = new HashMap<>();
        meta.put("type", "expectations");
        meta.put("source", "api");
        store.put("meta.json", "data".getBytes(StandardCharsets.UTF_8), meta);

        Optional<Blob> result = store.get("meta.json");
        assertTrue(result.isPresent());
        assertThat(result.get().getMetadata().get("type"), is("expectations"));
        assertThat(result.get().getMetadata().get("source"), is("api"));

        // Verify .meta file exists on disk
        assertTrue("metadata file should exist",
            Files.exists(baseDir.resolve("meta.json.meta")));
    }

    @Test
    public void shouldNotCreateMetaFileWhenMetadataEmpty() {
        store.put("nometa.json", "data".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
        assertFalse("no .meta file should be created",
            Files.exists(baseDir.resolve("nometa.json.meta")));
    }

    // --- list ---

    @Test
    public void shouldListByPrefix() {
        store.put("expectations/e1.json", "{}".getBytes(), Collections.emptyMap());
        store.put("expectations/e2.json", "{}".getBytes(), Collections.emptyMap());
        store.put("cassettes/c1.json", "{}".getBytes(), Collections.emptyMap());

        List<String> result = store.list("expectations/");
        assertThat(result, hasSize(2));
        assertThat(result, containsInAnyOrder("expectations/e1.json", "expectations/e2.json"));
    }

    @Test
    public void shouldReturnEmptyListForNonMatchingPrefix() {
        store.put("a/b.json", "data".getBytes(), Collections.emptyMap());
        assertThat(store.list("x/"), is(empty()));
    }

    @Test
    public void shouldExcludeMetaFilesFromListing() {
        Map<String, String> meta = new HashMap<>();
        meta.put("type", "test");
        store.put("expectations/e1.json", "{}".getBytes(), meta);

        List<String> result = store.list("expectations/");
        assertThat(result, hasSize(1));
        assertThat(result.get(0), is("expectations/e1.json"));
    }

    // --- delete ---

    @Test
    public void shouldDeleteBlob() {
        store.put("delete-me.json", "data".getBytes(), Collections.emptyMap());
        assertTrue(store.delete("delete-me.json"));
        assertFalse(store.get("delete-me.json").isPresent());
        assertFalse("file should not exist on disk", Files.exists(baseDir.resolve("delete-me.json")));
    }

    @Test
    public void shouldDeleteMetaFileWhenDeletingBlob() {
        Map<String, String> meta = new HashMap<>();
        meta.put("type", "test");
        store.put("with-meta.json", "data".getBytes(), meta);
        assertTrue("meta file should exist before delete",
            Files.exists(baseDir.resolve("with-meta.json.meta")));

        assertTrue(store.delete("with-meta.json"));
        assertFalse("meta file should be deleted",
            Files.exists(baseDir.resolve("with-meta.json.meta")));
    }

    @Test
    public void shouldReturnFalseWhenDeletingMissingKey() {
        assertFalse(store.delete("nonexistent.json"));
    }

    // --- on-disk parity with the pre-existing persistence write pattern ---

    @Test
    public void shouldWriteByteForByteIdenticalContent() throws Exception {
        // This test verifies that writing through the FilesystemBlobStore
        // produces the exact same on-disk bytes as the original
        // ExpectationFileSystemPersistence.updated() method: the bytes
        // are written to the file without any encoding or framing.
        String jsonContent = "[ {\n  \"httpRequest\" : {\n    \"path\" : \"/test\"\n  }\n} ]";
        byte[] data = jsonContent.getBytes(StandardCharsets.UTF_8);

        File targetFile = tempFolder.newFile("parity-test.json");
        String absoluteKey = targetFile.getAbsolutePath();

        store.put(absoluteKey, data, Collections.emptyMap());

        byte[] diskBytes = Files.readAllBytes(targetFile.toPath());
        assertArrayEquals("on-disk bytes should be identical to input bytes", data, diskBytes);
        assertThat("on-disk content should be identical to input string",
            new String(diskBytes, StandardCharsets.UTF_8), is(jsonContent));
    }

    @Test
    public void shouldHandleEmptyData() {
        store.put("empty.json", new byte[0], Collections.emptyMap());

        Optional<Blob> result = store.get("empty.json");
        assertTrue(result.isPresent());
        assertThat(result.get().getData().length, is(0));
    }

    @Test
    public void shouldHandleLargeData() {
        // Generate a large payload (1MB)
        byte[] largeData = new byte[1024 * 1024];
        Arrays.fill(largeData, (byte) 'x');
        store.put("large.bin", largeData, Collections.emptyMap());

        Optional<Blob> result = store.get("large.bin");
        assertTrue(result.isPresent());
        assertArrayEquals(largeData, result.get().getData());
    }

    // --- write failure propagation (not silently swallowed) ---

    @Test
    public void shouldThrowOnPutWhenDirectoryCannotBeCreated() {
        // Create a regular file where the store expects to create a directory,
        // so Files.createDirectories() will fail with an IOException.
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        Path blocker = baseDir.resolve("blocker");
        try {
            Files.write(blocker, "not a directory".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            fail("test setup failed: " + e);
        }

        try {
            // "blocker/child.json" requires "blocker" to be a directory, but it's a file
            store.put("blocker/child.json", data, Collections.emptyMap());
            fail("expected UncheckedIOException for unwritable directory");
        } catch (UncheckedIOException e) {
            assertThat(e.getMessage(), containsString("failed to create directories for blob"));
            assertNotNull("cause should be present", e.getCause());
        }
    }

    @Test
    public void shouldThrowOnPutWhenFileCannotBeCreated() throws Exception {
        // Create a directory where the store expects to write a file,
        // so Files.createFile() will fail because a directory already exists
        // with that name (not a FileAlreadyExistsException — that's a file).
        Path dir = baseDir.resolve("isADir");
        Files.createDirectories(dir);

        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        try {
            store.put("isADir", data, Collections.emptyMap());
            fail("expected UncheckedIOException when path is a directory");
        } catch (UncheckedIOException e) {
            assertThat(e.getMessage(), anyOf(
                containsString("failed to create blob file"),
                containsString("failed to write blob")
            ));
            assertNotNull("cause should be present", e.getCause());
        }
    }

    @Test
    public void shouldPropagateWriteFailureWithNullLogger() {
        // Verify that even with null logger, write failure throws rather than
        // silently swallowing — this is the critical data-loss fix.
        FilesystemBlobStore nullLoggerStore = new FilesystemBlobStore(baseDir, null);
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        Path blocker = baseDir.resolve("blocker2");
        try {
            Files.write(blocker, "not a directory".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            fail("test setup failed: " + e);
        }

        try {
            nullLoggerStore.put("blocker2/child.json", data, Collections.emptyMap());
            fail("expected UncheckedIOException even with null logger");
        } catch (UncheckedIOException e) {
            assertThat(e.getMessage(), containsString("failed to create directories for blob"));
        }
    }
}
