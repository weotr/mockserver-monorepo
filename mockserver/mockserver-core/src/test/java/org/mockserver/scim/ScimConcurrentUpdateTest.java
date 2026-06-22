package org.mockserver.scim;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.mock.crud.CrudDataStore;
import org.mockserver.model.CrudExpectationsDefinition.IdStrategy;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockserver.model.HttpRequest.request;

/**
 * Concurrency regression test for the SCIM single-resource read-modify-write path.
 *
 * <p>SCIM {@code PATCH} (and {@code PUT}) is a read-modify-write: the callback reads the current
 * resource, applies the change, and writes the result back. Before the fix the read and write were
 * two separate, individually-locked {@link CrudDataStore} calls with no lock spanning them, so two
 * concurrent PATCHes to the same resource could each read the same base state and the second write
 * would clobber the first — a lost update. These tests hammer a single resource from many threads
 * and assert that every concurrent change survives.
 */
public class ScimConcurrentUpdateTest {

    private static final String BASE_PATH = "/scim/v2";

    @Before
    public void setup() {
        ScimResourceStore.getInstance().reset();
    }

    @After
    public void teardown() {
        ScimResourceStore.getInstance().reset();
    }

    private ScimResourceStore.Provider registerProvider() {
        ScimResourceStore.Provider provider = new ScimResourceStore.Provider(
            BASE_PATH,
            new CrudDataStore("id", IdStrategy.UUID),
            new CrudDataStore("id", IdStrategy.UUID),
            false, null, true, true);
        ScimResourceStore.getInstance().registerProvider(provider);
        return provider;
    }

    private String createUser() {
        HttpRequest create = request(BASE_PATH + "/Users")
            .withMethod("POST")
            .withHeader("Host", "example.test")
            .withBody("{\"userName\":\"concurrent.user\"}");
        HttpResponse response = new ScimCollectionCallback().handle(create);
        assertThat(response.getStatusCode(), is(201));
        ObjectNode created = ScimCallbackSupport.parseObject(response.getBodyAsString());
        assertThat(created, notNullValue());
        return created.get("id").asText();
    }

    private HttpRequest patchRequest(String id, String body) {
        return request(BASE_PATH + "/Users/" + id)
            .withMethod("PATCH")
            .withHeader("Host", "example.test")
            .withBody(body);
    }

    /**
     * Many threads each PATCH a distinct top-level attribute of the same resource concurrently. With
     * a lost-update bug only a subset of the attributes survives; with the atomic read-modify-write
     * every attribute is present in the final state.
     */
    @Test
    public void concurrentPatchesOfDistinctAttributesAllSurvive() throws Exception {
        registerProvider();
        String id = createUser();
        ScimResourceStore.Provider provider = ScimResourceStore.getInstance().providerForBasePath(BASE_PATH);
        CrudDataStore store = provider.getUsers();

        int threads = 32;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger(0);

        try {
            for (int i = 0; i < threads; i++) {
                final String attribute = "attr" + i;
                executor.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        String body = "{\"schemas\":[\"" + ScimShaper.PATCH_OP_SCHEMA + "\"],"
                            + "\"Operations\":[{\"op\":\"add\",\"path\":\"" + attribute + "\",\"value\":\"set\"}]}";
                        HttpResponse response = new ScimResourceCallback().handle(patchRequest(id, body));
                        if (response.getStatusCode() != 200) {
                            failures.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS), is(true));
        } finally {
            executor.shutdownNow();
        }

        assertThat("all PATCH requests must succeed", failures.get(), is(0));

        ObjectNode finalState = store.getById(id);
        assertThat(finalState, notNullValue());
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            if (!finalState.has("attr" + i)) {
                missing.add("attr" + i);
            }
        }
        assertThat("no concurrent PATCH may be lost; missing=" + missing, missing.isEmpty(), is(true));
    }

    /**
     * Many threads each append a distinct value to the same array attribute concurrently. A lost
     * update drops elements; the atomic read-modify-write keeps every appended element.
     */
    @Test
    public void concurrentArrayAppendsAreNotLost() throws Exception {
        registerProvider();
        // seed the array attribute so the first append has something to append to
        String id = createUser();
        HttpResponse seed = new ScimResourceCallback().handle(patchRequest(id,
            "{\"schemas\":[\"" + ScimShaper.PATCH_OP_SCHEMA + "\"],"
                + "\"Operations\":[{\"op\":\"replace\",\"path\":\"tags\",\"value\":[]}]}"));
        assertThat(seed.getStatusCode(), is(200));

        ScimResourceStore.Provider provider = ScimResourceStore.getInstance().providerForBasePath(BASE_PATH);
        CrudDataStore store = provider.getUsers();

        int threads = 32;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger(0);

        try {
            for (int i = 0; i < threads; i++) {
                final int value = i;
                executor.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        String body = "{\"schemas\":[\"" + ScimShaper.PATCH_OP_SCHEMA + "\"],"
                            + "\"Operations\":[{\"op\":\"add\",\"path\":\"tags\",\"value\":\"v" + value + "\"}]}";
                        HttpResponse response = new ScimResourceCallback().handle(patchRequest(id, body));
                        if (response.getStatusCode() != 200) {
                            failures.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS), is(true));
        } finally {
            executor.shutdownNow();
        }

        assertThat("all PATCH requests must succeed", failures.get(), is(0));

        ObjectNode finalState = store.getById(id);
        assertThat(finalState, notNullValue());
        assertThat(finalState.get("tags"), notNullValue());
        assertThat("every appended element must be retained",
            finalState.get("tags").size(), is(threads));
    }

    /**
     * Concurrent PUT replacements race against each other. Each survivor must be a coherent,
     * fully-formed replacement (a well-defined last-writer-wins), never a partially-merged or
     * corrupted document, and the server-managed {@code meta.created} must be preserved throughout.
     */
    @Test
    public void concurrentPutsYieldCoherentLastWriterWins() throws Exception {
        registerProvider();
        String id = createUser();
        ScimResourceStore.Provider provider = ScimResourceStore.getInstance().providerForBasePath(BASE_PATH);
        CrudDataStore store = provider.getUsers();
        String createdTimestamp = store.getById(id).get("meta").get("created").asText();

        int threads = 32;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger(0);

        try {
            for (int i = 0; i < threads; i++) {
                final int marker = i;
                executor.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        String body = "{\"userName\":\"concurrent.user\",\"writer\":" + marker + "}";
                        HttpRequest put = request(BASE_PATH + "/Users/" + id)
                            .withMethod("PUT")
                            .withHeader("Host", "example.test")
                            .withBody(body);
                        HttpResponse response = new ScimResourceCallback().handle(put);
                        if (response.getStatusCode() != 200) {
                            failures.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failures.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS), is(true));
        } finally {
            executor.shutdownNow();
        }

        assertThat("all PUT requests must succeed", failures.get(), is(0));

        ObjectNode finalState = store.getById(id);
        assertThat(finalState, notNullValue());
        // the surviving document is a coherent full replacement (carries exactly one writer marker)
        assertThat(finalState.get("userName").asText(), is("concurrent.user"));
        assertThat(finalState.has("writer"), is(true));
        assertThat(finalState.get("writer").isInt(), is(true));
        // server-managed created timestamp preserved across every concurrent replacement
        assertThat(finalState.get("meta").get("created").asText(), is(createdTimestamp));
    }
}
