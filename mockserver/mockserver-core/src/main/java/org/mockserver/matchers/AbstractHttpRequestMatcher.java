package org.mockserver.matchers;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.model.RequestDefinition;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockserver.character.Character.NEW_LINE;

public abstract class AbstractHttpRequestMatcher extends NotMatcher<RequestDefinition> implements HttpRequestMatcher {

    protected static final String REQUEST_DID_NOT_MATCH = "request:{}didn't match";
    protected static final String REQUEST_MATCHER = " request matcher";
    protected static final String EXPECTATION = " expectation";
    protected static final String BECAUSE = ":{}because:{}";
    protected static final String REQUEST_DID_MATCH = "request:{}matched request:{}";
    protected static final String EXPECTATION_DID_MATCH = "request:{}matched" + EXPECTATION + ":{}";
    protected static final String DID_NOT_MATCH = " didn't match";
    protected static final String MATCHED = " matched";
    protected static final String COLON_NEW_LINES = ": " + NEW_LINE + NEW_LINE;

    protected final Configuration configuration;
    protected final MockServerLogger mockServerLogger;
    private int hashCode;
    private boolean isBlank = false;
    private volatile boolean responseInProgress = false;
    // Guards the lazy async removal of this matcher when it goes inactive. Several
    // data-plane scans (firstMatchingExpectation, retrieveActiveExpectations,
    // retrieveRequestMatchers) independently observe "!responseInProgress && !active"
    // and each would otherwise schedule a removal task for the same matcher under
    // concurrency. The removal is idempotent (the backing CircularPriorityQueue.remove
    // returns false the second time) so duplicates were never corrupting, but they were
    // wasteful — double scheduler load and a redundant backend remove() under churn.
    // This flag lets exactly the FIRST observer schedule the removal (CAS true once).
    private final AtomicBoolean removalScheduled = new AtomicBoolean(false);
    private MockServerMatcherNotifier.Cause source;
    protected boolean controlPlaneMatcher;
    protected Expectation expectation;
    protected String matcherDescription = "";
    protected String didNotMatchRequestBecause = REQUEST_DID_NOT_MATCH + REQUEST_MATCHER + BECAUSE;
    protected String didNotMatchExpectationBecause = REQUEST_DID_NOT_MATCH + EXPECTATION + BECAUSE;
    protected String didNotMatchExpectationWithoutBecause = REQUEST_DID_NOT_MATCH + EXPECTATION;

    protected AbstractHttpRequestMatcher(Configuration configuration, MockServerLogger mockServerLogger) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
    }

    public void setDescription(String description) {
        this.matcherDescription = description;
        didNotMatchRequestBecause = REQUEST_DID_NOT_MATCH + description + REQUEST_MATCHER + BECAUSE;
        didNotMatchExpectationBecause = REQUEST_DID_NOT_MATCH + description + EXPECTATION + BECAUSE;
        didNotMatchExpectationWithoutBecause = REQUEST_DID_NOT_MATCH + description + EXPECTATION;
    }

    @Override
    public boolean update(Expectation expectation) {
        if (this.expectation != null && this.expectation.equals(expectation)) {
            return false;
        } else {
            this.controlPlaneMatcher = false;
            this.expectation = expectation;
            this.hashCode = 0;
            this.isBlank = expectation.getHttpRequest() == null;
            // A matcher reused in place with a new expectation may become active
            // again (e.g. Times reset); allow its lazy removal to be re-armed.
            this.removalScheduled.set(false);
            apply(expectation.getHttpRequest());
            return true;
        }
    }


    @Override
    public boolean update(RequestDefinition requestDefinition) {
        this.controlPlaneMatcher = true;
        this.expectation = null;
        this.hashCode = 0;
        this.isBlank = requestDefinition == null;
        return apply(requestDefinition);
    }

    public void setControlPlaneMatcher(boolean controlPlaneMatcher) {
        this.controlPlaneMatcher = controlPlaneMatcher;
    }

    abstract boolean apply(RequestDefinition requestDefinition);

    @Override
    public boolean matches(RequestDefinition requestDefinition) {
        return matches(null, requestDefinition);
    }

    @Override
    public abstract boolean matches(MatchDifference context, RequestDefinition requestDefinition);

    @Override
    public Expectation getExpectation() {
        return expectation;
    }

    public boolean isResponseInProgress() {
        return responseInProgress;
    }

    public HttpRequestMatcher setResponseInProgress(boolean responseInProgress) {
        this.responseInProgress = responseInProgress;
        return this;
    }

    /**
     * Atomically claims the right to schedule this matcher's lazy async removal.
     * Returns {@code true} for the FIRST caller only; all subsequent concurrent
     * callers observing the same inactive matcher get {@code false} and must NOT
     * schedule a duplicate removal. This deduplicates the otherwise-redundant
     * {@code scheduler.submit(removeHttpRequestMatcher(...))} that several
     * data-plane scans would each fire for the same matcher.
     */
    public boolean tryScheduleRemoval() {
        return removalScheduled.compareAndSet(false, true);
    }

    public MockServerMatcherNotifier.Cause getSource() {
        return source;
    }

    public AbstractHttpRequestMatcher withSource(MockServerMatcherNotifier.Cause source) {
        this.source = source;
        return this;
    }

    public boolean isBlank() {
        return isBlank;
    }

    @Override
    public boolean isActive() {
        return expectation == null || expectation.isActive();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        AbstractHttpRequestMatcher that = (AbstractHttpRequestMatcher) o;
        return Objects.equals(expectation, that.expectation);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(expectation);
        }
        return hashCode;
    }

    protected static boolean applyNotOperators(boolean baseMatches, boolean requestNot, boolean expectationNot, boolean matcherNot) {
        boolean result = baseMatches;
        if (matcherNot) {
            result = !result;
        }
        if (expectationNot) {
            result = !result;
        }
        if (requestNot) {
            result = !result;
        }
        return result;
    }
}
