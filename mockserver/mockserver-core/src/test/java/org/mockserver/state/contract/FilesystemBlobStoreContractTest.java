package org.mockserver.state.contract;

import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockserver.state.BlobStore;
import org.mockserver.state.FilesystemBlobStore;

/**
 * Runs the {@link BlobStoreContract} against the {@link FilesystemBlobStore}.
 */
public class FilesystemBlobStoreContractTest extends BlobStoreContract {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Override
    protected BlobStore createStore() {
        return new FilesystemBlobStore(tempFolder.getRoot().toPath(), null);
    }
}
