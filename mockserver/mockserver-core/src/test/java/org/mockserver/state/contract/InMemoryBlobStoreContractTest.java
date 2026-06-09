package org.mockserver.state.contract;

import org.mockserver.state.BlobStore;
import org.mockserver.state.InMemoryBlobStore;

/**
 * Runs the {@link BlobStoreContract} against the {@link InMemoryBlobStore}.
 */
public class InMemoryBlobStoreContractTest extends BlobStoreContract {

    @Override
    protected BlobStore createStore() {
        return new InMemoryBlobStore();
    }
}
