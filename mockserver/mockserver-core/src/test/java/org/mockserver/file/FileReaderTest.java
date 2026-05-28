package org.mockserver.file;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;

public class FileReaderTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    // readFileFromClassPathOrPath

    @Test
    public void shouldReadFileFromClasspath() {
        // CertificateAuthorityCertificate.pem exists on the classpath in mockserver-core
        String content = FileReader.readFileFromClassPathOrPath("org/mockserver/socket/CertificateAuthorityCertificate.pem");
        assertThat(content, is(notNullValue()));
        assertThat(content.length(), is(greaterThan(0)));
    }

    @Test
    public void shouldReadFileFromFilesystemPath() throws IOException {
        File tempFile = temporaryFolder.newFile("test-read.txt");
        Files.write(tempFile.toPath(), "hello world".getBytes(StandardCharsets.UTF_8));

        String content = FileReader.readFileFromClassPathOrPath(tempFile.getAbsolutePath());
        assertThat(content, is("hello world"));
    }

    @Test
    public void shouldThrowRuntimeExceptionForNonExistentFile() {
        RuntimeException exception = assertThrows(RuntimeException.class, () ->
            FileReader.readFileFromClassPathOrPath("/nonexistent/path/to/file.txt")
        );
        assertThat(exception.getMessage(), containsString("/nonexistent/path/to/file.txt"));
    }

    @Test
    public void shouldReadUtf8Content() throws IOException {
        File tempFile = temporaryFolder.newFile("utf8.txt");
        Files.write(tempFile.toPath(), "unicode: éàüñ".getBytes(StandardCharsets.UTF_8));

        String content = FileReader.readFileFromClassPathOrPath(tempFile.getAbsolutePath());
        assertThat(content, is("unicode: éàüñ"));
    }

    @Test
    public void shouldReadEmptyFile() throws IOException {
        File tempFile = temporaryFolder.newFile("empty.txt");

        String content = FileReader.readFileFromClassPathOrPath(tempFile.getAbsolutePath());
        assertThat(content, is(""));
    }

    // openStreamToFileFromClassPathOrPath

    @Test
    public void shouldOpenStreamForClasspathResource() throws Exception {
        try (InputStream stream = FileReader.openStreamToFileFromClassPathOrPath("org/mockserver/socket/CertificateAuthorityCertificate.pem")) {
            assertThat(stream, is(notNullValue()));
            assertThat(stream.available(), is(greaterThan(0)));
        }
    }

    @Test
    public void shouldOpenStreamForFilesystemFile() throws Exception {
        File tempFile = temporaryFolder.newFile("stream-test.txt");
        Files.write(tempFile.toPath(), "stream content".getBytes(StandardCharsets.UTF_8));

        try (InputStream stream = FileReader.openStreamToFileFromClassPathOrPath(tempFile.getAbsolutePath())) {
            assertThat(stream, is(notNullValue()));
            byte[] bytes = new byte[14];
            int read = stream.read(bytes);
            assertThat(read, is(14));
            assertThat(new String(bytes, StandardCharsets.UTF_8), is("stream content"));
        }
    }

    @Test
    public void shouldThrowFileNotFoundExceptionForMissingFile() {
        assertThrows(FileNotFoundException.class, () ->
            FileReader.openStreamToFileFromClassPathOrPath("/nonexistent/absolute/path.txt")
        );
    }

    // openReaderToFileFromClassPathOrPath

    @Test
    public void shouldOpenReaderForClasspathResource() throws Exception {
        try (Reader reader = FileReader.openReaderToFileFromClassPathOrPath("org/mockserver/socket/CertificateAuthorityCertificate.pem")) {
            assertThat(reader, is(notNullValue()));
            assertThat(reader.ready(), is(true));
        }
    }

    @Test
    public void shouldOpenReaderForFilesystemFile() throws Exception {
        File tempFile = temporaryFolder.newFile("reader-test.txt");
        Files.write(tempFile.toPath(), "reader content".getBytes(StandardCharsets.UTF_8));

        try (Reader reader = FileReader.openReaderToFileFromClassPathOrPath(tempFile.getAbsolutePath())) {
            char[] chars = new char[14];
            int read = reader.read(chars);
            assertThat(read, is(14));
            assertThat(new String(chars), is("reader content"));
        }
    }

    @Test
    public void shouldThrowFileNotFoundExceptionForMissingFileViaReader() {
        assertThrows(FileNotFoundException.class, () ->
            FileReader.openReaderToFileFromClassPathOrPath("/nonexistent/absolute/path.txt")
        );
    }
}
