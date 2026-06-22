package org.mockserver.matchers;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.ConditionalRequestDefinition;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Matcher for {@link ConditionalRequestDefinition} (if-then-else request matching).
 * <p>
 * Composes the existing {@link HttpRequestMatcher} machinery for each branch rather than
 * reimplementing matching: the {@code if}, {@code then} and {@code else} branches are each
 * compiled to their own {@link HttpRequestMatcher} via {@link MatcherBuilder}. Evaluation is:
 * </p>
 * <pre>
 *   if (ifMatcher.matches(request)) {
 *       result = thenMatcher.matches(request)
 *   } else {
 *       result = elseMatcher != null ? elseMatcher.matches(request) : true
 *   }
 * </pre>
 * <p>
 * The branch matchers are built in the same plane (control vs data) as this matcher so that
 * the conditional behaves consistently whether it is created from an {@link Expectation}
 * (data plane) or a {@link RequestDefinition} filter (control plane).
 * </p>
 * <p>
 * <strong>Concurrency:</strong> like {@link HttpRequestPropertiesMatcher}, a single instance is
 * shared between the control plane (which rebuilds the compiled branch matchers in {@link #apply}
 * — a single-writer path) and the data plane (which reads them in {@link #matches} from many Netty
 * event-loop threads). All per-criterion compiled state lives in an immutable {@link Compiled}
 * holder built fully inside {@code apply()} and published through a single {@code volatile}
 * reference ({@link #compiled}); {@code matches()} snapshots that reference once on entry, so it
 * sees either the whole old set of branch matchers or the whole new one — never a torn mix.
 * </p>
 *
 * @author jamesdbloom
 */
public class ConditionalRequestMatcher extends AbstractHttpRequestMatcher {

    private final MatcherBuilder matcherBuilder;
    /**
     * Fully-built, immutable compiled branch matchers. Reassigned (never mutated) by {@code apply()}
     * on the single-writer control-plane path and read by {@code matches()} on data-plane threads;
     * {@code volatile} provides the happens-before edge so a reader sees a wholly-built holder.
     */
    private volatile Compiled compiled = Compiled.EMPTY;

    /**
     * Immutable snapshot of the compiled branch matchers, published atomically via the single
     * {@code volatile} {@link ConditionalRequestMatcher#compiled} reference.
     */
    private static final class Compiled {

        private static final Compiled EMPTY = new Compiled(null, null, null, null, Collections.emptyList());

        private final ConditionalRequestDefinition conditionalRequestDefinition;
        private final HttpRequestMatcher ifMatcher;
        private final HttpRequestMatcher thenMatcher;
        private final HttpRequestMatcher elseMatcher;
        private final List<HttpRequest> httpRequests;

        private Compiled(
            ConditionalRequestDefinition conditionalRequestDefinition,
            HttpRequestMatcher ifMatcher,
            HttpRequestMatcher thenMatcher,
            HttpRequestMatcher elseMatcher,
            List<HttpRequest> httpRequests
        ) {
            this.conditionalRequestDefinition = conditionalRequestDefinition;
            this.ifMatcher = ifMatcher;
            this.thenMatcher = thenMatcher;
            this.elseMatcher = elseMatcher;
            this.httpRequests = httpRequests;
        }
    }

    protected ConditionalRequestMatcher(Configuration configuration, MockServerLogger mockServerLogger) {
        super(configuration, mockServerLogger);
        this.matcherBuilder = new MatcherBuilder(configuration, mockServerLogger);
    }

    @Override
    public List<HttpRequest> getHttpRequests() {
        return compiled.httpRequests;
    }

    @Override
    public boolean apply(RequestDefinition requestDefinition) {
        ConditionalRequestDefinition conditional = requestDefinition instanceof ConditionalRequestDefinition definition ? definition : null;
        Compiled current = this.compiled;
        if (current.conditionalRequestDefinition == null || !current.conditionalRequestDefinition.equals(conditional)) {
            Compiled rebuilt;
            if (conditional != null) {
                HttpRequestMatcher ifMatcher = buildBranchMatcher(conditional.getIf());
                HttpRequestMatcher thenMatcher = buildBranchMatcher(conditional.getThen());
                HttpRequestMatcher elseMatcher = buildBranchMatcher(conditional.getElse());
                // surface the then/else branch requests for logging and verification; the
                // if branch is the guard, not a request the expectation responds to
                List<HttpRequest> requests = new ArrayList<>();
                addBranchRequests(requests, thenMatcher);
                addBranchRequests(requests, elseMatcher);
                rebuilt = new Compiled(conditional, ifMatcher, thenMatcher, elseMatcher, Collections.unmodifiableList(requests));
            } else {
                rebuilt = new Compiled(null, null, null, null, Collections.emptyList());
            }
            // single volatile publish — the happens-before edge that gives data-plane readers a
            // wholly-built (never torn) view of the new branch matchers
            this.compiled = rebuilt;
            return true;
        }
        return false;
    }

    private void addBranchRequests(List<HttpRequest> requests, HttpRequestMatcher branchMatcher) {
        if (branchMatcher != null && branchMatcher.getHttpRequests() != null) {
            for (HttpRequest request : branchMatcher.getHttpRequests()) {
                if (request != null) {
                    requests.add(request);
                }
            }
        }
    }

    private HttpRequestMatcher buildBranchMatcher(RequestDefinition branch) {
        if (branch == null) {
            return null;
        }
        if (controlPlaneMatcher) {
            // control-plane filter: build the matcher directly from the request definition,
            // which marks the child matcher as a control-plane matcher
            return matcherBuilder.transformsToMatcher(branch);
        } else {
            // data-plane: wrap the branch in a throwaway expectation so the child matcher is
            // built as a data-plane matcher (controlPlaneMatcher == false)
            return matcherBuilder.transformsToMatcher(new Expectation(branch));
        }
    }

    @Override
    public boolean matches(MatchDifference context, RequestDefinition requestDefinition) {
        if (!isActive()) {
            return false;
        }
        // snapshot the compiled branch matchers once so the whole match runs against a single,
        // wholly-built version even if the control plane rebuilds it concurrently
        final Compiled compiled = this.compiled;
        boolean requestNot = requestDefinition != null && requestDefinition.isNot();
        boolean conditionalNot = compiled.conditionalRequestDefinition != null && compiled.conditionalRequestDefinition.isNot();
        if (compiled.ifMatcher == null) {
            // a conditional with no guard cannot be evaluated; treat as no-match
            return applyNotOperators(false, requestNot, conditionalNot, not);
        }
        boolean guardMatches = compiled.ifMatcher.matches(context, requestDefinition);
        boolean branchMatches;
        if (guardMatches) {
            branchMatches = compiled.thenMatcher == null || compiled.thenMatcher.matches(context, requestDefinition);
        } else {
            // else branch absent => match whenever the guard is false
            branchMatches = compiled.elseMatcher == null || compiled.elseMatcher.matches(context, requestDefinition);
        }
        return applyNotOperators(branchMatches, requestNot, conditionalNot, not);
    }
}
