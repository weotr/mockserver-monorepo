package org.mockserver.state.contract;

import org.mockserver.state.InMemoryKeyValueStore;
import org.mockserver.state.KeyValueStore;

/**
 * Runs the full {@link KeyValueStoreContract} against the in-memory
 * implementation, verifying it satisfies the SPI guarantees.
 */
public class InMemoryKeyValueStoreContractTest extends KeyValueStoreContract {

    @Override
    protected KeyValueStore<String> createStore() {
        return new InMemoryKeyValueStore<>();
    }
}
