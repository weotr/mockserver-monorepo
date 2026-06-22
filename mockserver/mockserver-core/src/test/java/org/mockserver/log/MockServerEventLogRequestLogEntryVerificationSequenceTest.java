package org.mockserver.log;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;

import java.util.concurrent.CompletableFuture;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.fail;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
public class MockServerEventLogRequestLogEntryVerificationSequenceTest {

    private static final Scheduler scheduler = new Scheduler(configuration(), new MockServerLogger());
    private final Configuration configuration = configuration();
    private MockServerEventLog mockServerEventLog;

    @Before
    public void setupTestFixture() {
        // the exact-match (is(...)) assertions below expect the message WITHOUT a closest-match diff,
        // so keep detailed failures off by default; the diff-specific tests opt in explicitly
        configuration.detailedVerificationFailures(false);
        mockServerEventLog = new MockServerEventLog(configuration, new MockServerLogger(configuration, MockServerLogger.class), scheduler, true);
    }

    @AfterClass
    public static void stopScheduler() {
        scheduler.shutdown();
    }

    public String verify(Verification verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        mockServerEventLog.verify(verification, result::complete);
        try {
            return result.get(10, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
        }
    }

    public String verify(VerificationSequence verificationSequence) {
        CompletableFuture<String> result = new CompletableFuture<>();
        mockServerEventLog.verify(verificationSequence, result::complete);
        try {
            return result.get(10, SECONDS);
        } catch (Exception e) {
            fail(e.getMessage());
            return null;
        }
    }

    @Test
    public void shouldPassVerificationWithNullRequest() {
        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("four"))
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify((VerificationSequence) null), is(""));
    }

    @Test
    public void shouldFailVerificationSequenceWithNoRequest() {
        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("four"))
                .setType(RECEIVED_REQUEST)
        );

        // then -- an entirely-empty sequence (no expectationIds, requests or responses) is rejected, not vacuously passed
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(

                    )
            ),
            is("No expectations, requests or responses specified in verification sequence"));
    }

    @Test
    public void shouldPassVerificationSequenceWithOneRequest() {
        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("four"))
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("one")
                    )
            ),
            is(""));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("multi")
                    )
            ),
            is(""));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("three")
                    )
            ),
            is(""));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("four")
                    )
            ),
            is(""));
    }

    @Test
    public void shouldPassVerificationSequenceWithTwoRequests() {
        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("four"))
                .setType(RECEIVED_REQUEST)
        );

        // then - next to each other
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("one"),
                        request("multi")
                    )
            ),
            is(""));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("multi"),
                        request("three")
                    )
            ),
            is(""));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("three"),
                        request("multi")
                    )
            ),
            is(""));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("multi"),
                        request("four")
                    )
            ),
            is(""));
        // then - not next to each other
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("one"),
                        request("three")
                    )
            ),
            is(""));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("one"),
                        request("four")
                    )
            ),
            is(""));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("multi"),
                        request("multi")
                    )
            ),
            is(""));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("three"),
                        request("four")
                    )
            ),
            is(""));
    }

    @Test
    public void shouldFailVerificationSequenceWithOneRequest() {
        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("four"))
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("five")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"five\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
    }

    @Test
    public void shouldFailVerificationSequenceWithTwoRequestsWrongOrder() {
        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("four"))
                .setType(RECEIVED_REQUEST)
        );

        // then - next to each other
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("multi"),
                        request("one")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("four"),
                        request("multi")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
        // then - not next to each other
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("three"),
                        request("one")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("four"),
                        request("one")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("four"),
                        request("three")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
    }

    @Test
    public void shouldFailVerificationSequenceWithLimitedReturnedRequestsViaConfiguration() {
        // given
        configuration.maximumNumberOfRequestToReturnInVerificationFailure(1);

        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("four"))
                .setType(RECEIVED_REQUEST)
        );

        // then - next to each other
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("multi"),
                        request("one")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "} ]> but was not found, found 5 other requests"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("four"),
                        request("multi")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "} ]> but was not found, found 5 other requests"));
        // then - not next to each other
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("three"),
                        request("one")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "} ]> but was not found, found 5 other requests"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("four"),
                        request("one")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "} ]> but was not found, found 5 other requests"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("four"),
                        request("three")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "} ]> but was not found, found 5 other requests"));
    }

    @Test
    public void shouldFailVerificationSequenceWithLimitedReturnedRequestsViaVerificationSequence() {
        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("four"))
                .setType(RECEIVED_REQUEST)
        );

        // then - next to each other
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("multi"),
                        request("one")
                    )
                    .withMaximumNumberOfRequestToReturnInVerificationFailure(1)
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "} ]> but was not found, found 5 other requests"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("four"),
                        request("multi")
                    )
                    .withMaximumNumberOfRequestToReturnInVerificationFailure(1)
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "} ]> but was not found, found 5 other requests"));
        // then - not next to each other
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("three"),
                        request("one")
                    )
                    .withMaximumNumberOfRequestToReturnInVerificationFailure(1)
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "} ]> but was not found, found 5 other requests"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("four"),
                        request("one")
                    )
                    .withMaximumNumberOfRequestToReturnInVerificationFailure(1)
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "} ]> but was not found, found 5 other requests"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("four"),
                        request("three")
                    )
                    .withMaximumNumberOfRequestToReturnInVerificationFailure(1)
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "} ]> but was not found, found 5 other requests"));
    }

    @Test
    public void shouldFailVerificationSequenceWithTwoRequestsFirstIncorrect() {
        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("four"))
                .setType(RECEIVED_REQUEST)
        );

        // then - next to each other
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("zero"),
                        request("multi")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"zero\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("zero"),
                        request("three")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"zero\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("zero"),
                        request("four")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"zero\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
    }

    @Test
    public void shouldFailVerificationSequenceWithTwoRequestsSecondIncorrect() {
        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("four"))
                .setType(RECEIVED_REQUEST)
        );

        // then - next to each other
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("one"),
                        request("five")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"five\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("multi"),
                        request("five")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"five\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("three"),
                        request("five")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"five\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
    }

    @Test
    public void shouldFailVerificationSequenceWithThreeRequestsWrongOrder() {
        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("four"))
                .setType(RECEIVED_REQUEST)
        );

        // then - next to each other
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("one"),
                        request("four"),
                        request("multi")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("one"),
                        request("multi"),
                        request("one")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
        // then - not next to each other
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("four"),
                        request("one"),
                        request("multi")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("multi"),
                        request("three"),
                        request("one")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));

    }

    @Test
    public void shouldFailVerificationSequenceWithThreeRequestsDuplicateMissing() {
        // when
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("one"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("three"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("multi"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request("four"))
                .setType(RECEIVED_REQUEST)
        );

        // then
        assertThat(verify(
                new VerificationSequence()
                    .withRequests(
                        request("multi"),
                        request("multi"),
                        request("multi")
                    )
            ),
            is("Request sequence not found, expected:<[ {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "} ]> but was:<[ {" + NEW_LINE +
                "  \"path\" : \"one\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"three\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"multi\"" + NEW_LINE +
                "}, {" + NEW_LINE +
                "  \"path\" : \"four\"" + NEW_LINE +
                "} ]>"));
    }

    @Test
    public void shouldIncludeClosestMatchDiffInSequenceFailureWhenEnabled() {
        // given - detailed failures on
        configuration.detailedVerificationFailures(true);
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request().withMethod("POST").withPath("some_path"))
                .setType(RECEIVED_REQUEST)
        );

        // when - the (only) sequence step does not match the recorded request
        String result = verify(
            new VerificationSequence()
                .withRequests(
                    request().withMethod("GET").withPath("some_other_path")
                )
        );

        // then - the failure carries a field-level closest-match diff for the failing step
        assertThat(result, containsString("Request sequence not found"));
        assertThat(result, containsString("closest match diff:"));
        assertThat(result, containsString("method:"));
    }

    @Test
    public void shouldIncludeClosestMatchDiffForTheSpecificFailingStep() {
        // given - detailed failures on; first step will match, second step will not
        configuration.detailedVerificationFailures(true);
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request().withMethod("GET").withPath("first"))
                .setType(RECEIVED_REQUEST)
        );
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request().withMethod("GET").withPath("second_actual"))
                .setType(RECEIVED_REQUEST)
        );

        // when - step one matches "first"; step two ("second_expected") finds no later match
        String result = verify(
            new VerificationSequence()
                .withRequests(
                    request().withMethod("GET").withPath("first"),
                    request().withMethod("GET").withPath("second_expected")
                )
        );

        // then - the diff is for the failing step's closest actual request (path differs)
        assertThat(result, containsString("Request sequence not found"));
        assertThat(result, containsString("closest match diff:"));
        assertThat(result, containsString("path:"));
    }

    @Test
    public void shouldNotIncludeClosestMatchDiffInSequenceFailureWhenDisabled() {
        // given - detailed failures off
        configuration.detailedVerificationFailures(false);
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request().withMethod("POST").withPath("some_path"))
                .setType(RECEIVED_REQUEST)
        );

        // when
        String result = verify(
            new VerificationSequence()
                .withRequests(
                    request().withMethod("GET").withPath("some_other_path")
                )
        );

        // then - no diff appended; back-compat message preserved
        assertThat(result, containsString("Request sequence not found"));
        assertThat(result, not(containsString("closest match diff:")));
    }

    @Test
    public void shouldNotIncludeClosestMatchDiffWhenNoRequestsLogged() {
        // given - detailed failures on but nothing recorded
        configuration.detailedVerificationFailures(true);

        // when
        String result = verify(
            new VerificationSequence()
                .withRequests(
                    request().withMethod("GET").withPath("some_path")
                )
        );

        // then - nothing to diff against, so no diff appended
        assertThat(result, containsString("Request sequence not found"));
        assertThat(result, not(containsString("closest match diff:")));
    }

    @Test
    public void shouldIncludeClosestResponseDiffInResponseSequenceFailureWhenEnabled() {
        // given - detailed failures on; one recorded request-response pair with a 200 response
        configuration.detailedVerificationFailures(true);
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request().withPath("some_path"))
                .setHttpResponse(response().withStatusCode(200))
                .setType(EXPECTATION_RESPONSE)
        );

        // when - verifying a response sequence expecting a 404 (recorded is 200)
        String result = verify(
            new VerificationSequence()
                .withResponses(
                    response().withStatusCode(404)
                )
        );

        // then - the failure carries a field-level closest-response diff for the failing step
        assertThat(result, containsString("Response sequence not found"));
        assertThat(result, containsString("closest match diff:"));
    }

    @Test
    public void shouldNotIncludeClosestResponseDiffInResponseSequenceFailureWhenDisabled() {
        // given - detailed failures off
        configuration.detailedVerificationFailures(false);
        mockServerEventLog.add(
            new LogEntry()
                .setHttpRequest(request().withPath("some_path"))
                .setHttpResponse(response().withStatusCode(200))
                .setType(EXPECTATION_RESPONSE)
        );

        // when
        String result = verify(
            new VerificationSequence()
                .withResponses(
                    response().withStatusCode(404)
                )
        );

        // then - no diff appended; back-compat message preserved
        assertThat(result, containsString("Response sequence not found"));
        assertThat(result, not(containsString("closest match diff:")));
    }

}
