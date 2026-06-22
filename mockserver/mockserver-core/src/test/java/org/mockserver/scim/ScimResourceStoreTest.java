package org.mockserver.scim;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.mock.crud.CrudDataStore;
import org.mockserver.model.CrudExpectationsDefinition.IdStrategy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class ScimResourceStoreTest {

    @Before
    public void setup() {
        ScimResourceStore.getInstance().reset();
    }

    @After
    public void teardown() {
        ScimResourceStore.getInstance().reset();
    }

    private ScimResourceStore.Provider provider(String basePath) {
        return new ScimResourceStore.Provider(
            basePath,
            new CrudDataStore("id", IdStrategy.UUID),
            new CrudDataStore("id", IdStrategy.UUID),
            false, null, true, true);
    }

    @Test
    public void resolvesProviderByPathPrefix() {
        ScimResourceStore store = ScimResourceStore.getInstance();
        store.registerProvider(provider("/scim/v2"));

        assertThat(store.providerForRequestPath("/scim/v2/Users"), notNullValue());
        assertThat(store.providerForRequestPath("/scim/v2/Users/123"), notNullValue());
        assertThat(store.providerForRequestPath("/scim/v2"), notNullValue());
        assertThat(store.providerForRequestPath("/other/Users"), nullValue());
    }

    @Test
    public void perBasePathIsolation() {
        ScimResourceStore store = ScimResourceStore.getInstance();
        store.registerProvider(provider("/scim/v2"));
        store.registerProvider(provider("/tenant-a/scim"));

        ScimResourceStore.Provider a = store.providerForRequestPath("/scim/v2/Users");
        ScimResourceStore.Provider b = store.providerForRequestPath("/tenant-a/scim/Users");
        assertThat(a.getBasePath(), is("/scim/v2"));
        assertThat(b.getBasePath(), is("/tenant-a/scim"));
        // distinct backing stores
        assertThat(a.getUsers() == b.getUsers(), is(false));
    }

    @Test
    public void longestPrefixWins() {
        ScimResourceStore store = ScimResourceStore.getInstance();
        store.registerProvider(provider("/scim"));
        store.registerProvider(provider("/scim/v2"));

        assertThat(store.providerForRequestPath("/scim/v2/Users").getBasePath(), is("/scim/v2"));
        assertThat(store.providerForRequestPath("/scim/Users").getBasePath(), is("/scim"));
    }

    @Test
    public void reRegisterReplaces() {
        ScimResourceStore store = ScimResourceStore.getInstance();
        store.registerProvider(provider("/scim/v2"));
        ScimResourceStore.Provider first = store.providerForBasePath("/scim/v2");
        store.registerProvider(provider("/scim/v2"));
        ScimResourceStore.Provider second = store.providerForBasePath("/scim/v2");
        assertThat(first == second, is(false));
    }

    @Test
    public void resetClears() {
        ScimResourceStore store = ScimResourceStore.getInstance();
        store.registerProvider(provider("/scim/v2"));
        store.reset();
        assertThat(store.providerForRequestPath("/scim/v2/Users"), nullValue());
    }
}
