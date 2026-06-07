package org.mockserver.persistence;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.state.FilesystemBlobStore;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests that persistence routed through a {@link FilesystemBlobStore} writes
 * the SAME files in the SAME place with the SAME content as the original
 * direct-file-I/O implementation. This is the on-disk parity gate for the
 * G10 Phase 2d BlobStore refactor.
 */
public class BlobStoreRoutedPersistenceTest {

    private MockServerLogger mockServerLogger;
    private RequestMatchers requestMatchers;

    @Before
    public void createMockServerMatcher() {
        Configuration matcherConfiguration = configuration();
        mockServerLogger = new MockServerLogger(matcherConfiguration, BlobStoreRoutedPersistenceTest.class);
        requestMatchers = new RequestMatchers(matcherConfiguration, mockServerLogger, new Scheduler(matcherConfiguration, mockServerLogger), new WebSocketClientRegistry(matcherConfiguration, mockServerLogger));
    }

    @Test
    public void shouldPersistViaBlobStoreToSameFileWithIdenticalContent() throws Exception {
        // given — a temp file for the persisted expectations
        File persistedExpectations = File.createTempFile("blobStoreParity", ".json");
        Configuration configuration = configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persistedExpectations.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, BlobStoreRoutedPersistenceTest.class);

        // Create a FilesystemBlobStore and pass it explicitly to the persistence class
        FilesystemBlobStore blobStore = new FilesystemBlobStore(logger);
        ExpectationFileSystemPersistence persistence = null;
        try {
            // when — persist expectations through the BlobStore
            persistence = new ExpectationFileSystemPersistence(configuration, logger, requestMatchers, blobStore);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleFirst")
            )
                .withId("one")
                .thenRespond(
                    response()
                        .withBody("some first response")
                ), API);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/simpleSecond")
            )
                .withId("two")
                .thenRespond(
                    response()
                        .withBody("some second response")
                ), API);
            MILLISECONDS.sleep(1500);

            // then — the file on disk should contain the exact same JSON
            String expectedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleFirst\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some first response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"one\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/simpleSecond\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"some second response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"two\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";

            // Assert the file path is the SAME as the configured path
            assertThat("file should be written at the configured path",
                persistedExpectations.exists(), is(true));

            // Assert the content is byte-for-byte identical
            String actualContent = new String(Files.readAllBytes(persistedExpectations.toPath()), StandardCharsets.UTF_8);
            assertThat(persistedExpectations.getAbsolutePath() + " does not match expected content",
                actualContent, is(expectedFileContents));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    @Test
    public void shouldPersistViaBlobStoreOnAddAndRemove() throws Exception {
        // given
        File persistedExpectations = File.createTempFile("blobStoreRemove", ".json");
        Configuration configuration = configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persistedExpectations.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, BlobStoreRoutedPersistenceTest.class);
        FilesystemBlobStore blobStore = new FilesystemBlobStore(logger);
        ExpectationFileSystemPersistence persistence = null;
        try {
            // when
            persistence = new ExpectationFileSystemPersistence(configuration, logger, requestMatchers, blobStore);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/first")
            )
                .withId("a")
                .thenRespond(
                    response()
                        .withBody("first response")
                ), API);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/second")
            )
                .withId("b")
                .thenRespond(
                    response()
                        .withBody("second response")
                ), API);
            // remove the first one
            requestMatchers.clear(request().withPath("/first"));
            MILLISECONDS.sleep(1500);

            // then — only the second expectation should be in the file
            String actualContent = new String(Files.readAllBytes(persistedExpectations.toPath()), StandardCharsets.UTF_8);
            String expectedFileContents = "[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/second\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"second response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"b\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]";
            assertThat(actualContent, is(expectedFileContents));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }

    @Test
    public void shouldWorkWithDefaultConstructor() throws Exception {
        // Verify the backwards-compatible constructor (no BlobStore arg)
        // still uses FilesystemBlobStore internally and produces correct output
        File persistedExpectations = File.createTempFile("blobStoreDefault", ".json");
        Configuration configuration = configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persistedExpectations.getAbsolutePath());
        MockServerLogger logger = new MockServerLogger(configuration, BlobStoreRoutedPersistenceTest.class);
        ExpectationFileSystemPersistence persistence = null;
        try {
            persistence = new ExpectationFileSystemPersistence(configuration, logger, requestMatchers);
            requestMatchers.add(new Expectation(
                request()
                    .withPath("/test")
            )
                .withId("x")
                .thenRespond(
                    response()
                        .withBody("test response")
                ), API);
            MILLISECONDS.sleep(1500);

            String actualContent = new String(Files.readAllBytes(persistedExpectations.toPath()), StandardCharsets.UTF_8);
            assertThat(actualContent, is("[ {" + NEW_LINE +
                "  \"httpRequest\" : {" + NEW_LINE +
                "    \"path\" : \"/test\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"httpResponse\" : {" + NEW_LINE +
                "    \"body\" : \"test response\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"id\" : \"x\"," + NEW_LINE +
                "  \"priority\" : 0," + NEW_LINE +
                "  \"timeToLive\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"times\" : {" + NEW_LINE +
                "    \"unlimited\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "} ]"));
        } finally {
            if (persistence != null) {
                persistence.stop();
            }
        }
    }
}
