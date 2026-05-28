package org.mockserver.file;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class FileCreatorTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldCreateFileWhenItDoesNotExist() {
        File file = new File(temporaryFolder.getRoot(), "newfile.txt");
        assertThat(file.exists(), is(false));

        File result = FileCreator.createFileIfNotExists("test", file);

        assertThat(result.exists(), is(true));
        assertThat(result.getAbsolutePath(), is(file.getAbsolutePath()));
    }

    @Test
    public void shouldReturnExistingFileWithoutError() throws IOException {
        File existing = temporaryFolder.newFile("existing.txt");
        assertThat(existing.exists(), is(true));

        File result = FileCreator.createFileIfNotExists("test", existing);

        assertThat(result.exists(), is(true));
        assertThat(result.getAbsolutePath(), is(existing.getAbsolutePath()));
    }

    @Test
    public void shouldCreateParentDirectories() {
        File file = new File(temporaryFolder.getRoot(), "a/b/c/deep.txt");
        assertThat(file.getParentFile().exists(), is(false));

        File result = FileCreator.createFileIfNotExists("test", file);

        assertThat(result.exists(), is(true));
        assertThat(result.getParentFile().isDirectory(), is(true));
    }

    @Test
    public void shouldCreateParentDirsForNestedPath() throws IOException {
        File nested = new File(temporaryFolder.getRoot(), "level1/level2/file.txt");

        FileCreator.createParentDirs(nested);

        assertThat(nested.getParentFile().exists(), is(true));
        assertThat(nested.getParentFile().isDirectory(), is(true));
    }

    @Test
    public void shouldHandleParentDirsWhenParentAlreadyExists() throws IOException {
        File file = new File(temporaryFolder.getRoot(), "existing-parent.txt");

        // should not throw
        FileCreator.createParentDirs(file);

        assertThat(file.getParentFile().isDirectory(), is(true));
    }

    @Test
    public void shouldHandleInvalidPathGracefully() {
        // When a file cannot be created (e.g. empty path in an impossible location),
        // createFileIfNotExists logs an error but does not throw
        File impossibleFile = new File("/\0invalid");
        // should not throw
        FileCreator.createFileIfNotExists("test", impossibleFile);
    }
}
