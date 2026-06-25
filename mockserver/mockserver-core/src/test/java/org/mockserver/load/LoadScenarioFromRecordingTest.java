package org.mockserver.load;

import org.junit.Test;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.fail;
import static org.mockserver.model.HttpRequest.request;

/**
 * Unit tests for {@link LoadScenarioFromRecording} — the pure record-to-load generator. These exercise
 * only the in-memory transformation from recorded {@link RequestDefinition}s to a {@link LoadScenario};
 * they touch no global state and so run in the parallel Surefire phase.
 */
public class LoadScenarioFromRecordingTest {

    private static List<RequestDefinition> recorded(HttpRequest... requests) {
        return new ArrayList<>(Arrays.asList(requests));
    }

    // --- VERBATIM ---

    @Test
    public void verbatimProducesOneStepPerRecordedRequestPreservingMethodPathBodyInOrder() {
        List<RequestDefinition> recorded = recorded(
            request().withMethod("GET").withPath("/orders/1"),
            request().withMethod("POST").withPath("/orders").withBody("{\"item\":\"a\"}"),
            request().withMethod("GET").withPath("/orders/2")
        );

        LoadScenario scenario = LoadScenarioFromRecording.generate("rec", recorded, LoadScenarioFromRecording.Mode.VERBATIM, null, null, null);

        assertThat(scenario.getSteps().size(), is(3));
        assertThat(scenario.getSteps().get(0).getRequest().getMethod().getValue(), is("GET"));
        assertThat(scenario.getSteps().get(0).getRequest().getPath().getValue(), is("/orders/1"));
        assertThat(scenario.getSteps().get(1).getRequest().getMethod().getValue(), is("POST"));
        assertThat(scenario.getSteps().get(1).getRequest().getPath().getValue(), is("/orders"));
        assertThat(scenario.getSteps().get(1).getRequest().getBodyAsString(), is("{\"item\":\"a\"}"));
        assertThat(scenario.getSteps().get(2).getRequest().getPath().getValue(), is("/orders/2"));
    }

    @Test
    public void verbatimIsTheDefaultModeWhenNull() {
        LoadScenario scenario = LoadScenarioFromRecording.generate("rec",
            recorded(request().withMethod("GET").withPath("/a"), request().withMethod("GET").withPath("/b")),
            null, null, null, null);
        assertThat(scenario.getSteps().size(), is(2));
        assertThat(scenario.getSteps().get(0).getRequest().getPath().getValue(), is("/a"));
    }

    @Test
    public void verbatimTruncatesToMaxSteps() {
        List<RequestDefinition> recorded = recorded(
            request().withMethod("GET").withPath("/a"),
            request().withMethod("GET").withPath("/b"),
            request().withMethod("GET").withPath("/c")
        );
        LoadScenario scenario = LoadScenarioFromRecording.generate("rec", recorded, LoadScenarioFromRecording.Mode.VERBATIM, 2, null, null);
        assertThat(scenario.getSteps().size(), is(2));
        assertThat(scenario.getSteps().get(0).getRequest().getPath().getValue(), is("/a"));
        assertThat(scenario.getSteps().get(1).getRequest().getPath().getValue(), is("/b"));
    }

    @Test
    public void verbatimIgnoresNonPositiveOrOversizedMaxSteps() {
        List<RequestDefinition> recorded = recorded(
            request().withMethod("GET").withPath("/a"),
            request().withMethod("GET").withPath("/b")
        );
        assertThat(LoadScenarioFromRecording.generate("rec", recorded, LoadScenarioFromRecording.Mode.VERBATIM, 0, null, null).getSteps().size(), is(2));
        assertThat(LoadScenarioFromRecording.generate("rec", recorded, LoadScenarioFromRecording.Mode.VERBATIM, 99, null, null).getSteps().size(), is(2));
    }

    // --- TEMPLATIZED ---

    @Test
    public void templatizedDedupesIdSegmentsIntoOneRouteStep() {
        List<RequestDefinition> recorded = recorded(
            request().withMethod("GET").withPath("/orders/1"),
            request().withMethod("GET").withPath("/orders/2"),
            request().withMethod("GET").withPath("/orders/3")
        );
        LoadScenario scenario = LoadScenarioFromRecording.generate("rec", recorded, LoadScenarioFromRecording.Mode.TEMPLATIZED, null, null, null);
        assertThat(scenario.getSteps().size(), is(1));
        // the single step keeps the first concrete example, and is named with the templatised route
        assertThat(scenario.getSteps().get(0).getRequest().getPath().getValue(), is("/orders/1"));
        assertThat(scenario.getSteps().get(0).getName(), is("GET /orders/{id}"));
    }

    @Test
    public void templatizedOrdersRoutesByDescendingFrequency() {
        List<RequestDefinition> recorded = recorded(
            // /products hit once
            request().withMethod("GET").withPath("/products/9"),
            // /orders hit three times (most-hit, should come first)
            request().withMethod("GET").withPath("/orders/1"),
            request().withMethod("GET").withPath("/orders/2"),
            request().withMethod("GET").withPath("/orders/3"),
            // /users hit twice
            request().withMethod("GET").withPath("/users/7"),
            request().withMethod("GET").withPath("/users/8")
        );
        LoadScenario scenario = LoadScenarioFromRecording.generate("rec", recorded, LoadScenarioFromRecording.Mode.TEMPLATIZED, null, null, null);
        assertThat(scenario.getSteps().size(), is(3));
        assertThat(scenario.getSteps().get(0).getName(), is("GET /orders/{id}"));
        assertThat(scenario.getSteps().get(1).getName(), is("GET /users/{id}"));
        assertThat(scenario.getSteps().get(2).getName(), is("GET /products/{id}"));
    }

    @Test
    public void templatizedWeightsEachStepByHitCountAndSelectsWeighted() {
        List<RequestDefinition> recorded = recorded(
            // /products hit once
            request().withMethod("GET").withPath("/products/9"),
            // /orders hit three times (most-hit)
            request().withMethod("GET").withPath("/orders/1"),
            request().withMethod("GET").withPath("/orders/2"),
            request().withMethod("GET").withPath("/orders/3"),
            // /users hit twice
            request().withMethod("GET").withPath("/users/7"),
            request().withMethod("GET").withPath("/users/8")
        );
        LoadScenario scenario = LoadScenarioFromRecording.generate("rec", recorded, LoadScenarioFromRecording.Mode.TEMPLATIZED, null, null, null);
        // WEIGHTED selection reproduces the recorded mix.
        assertThat(scenario.getStepSelection(), is(LoadScenario.StepSelection.WEIGHTED));
        // Steps ordered by descending frequency with weight == hit count.
        assertThat(scenario.getSteps().size(), is(3));
        assertThat(scenario.getSteps().get(0).getName(), is("GET /orders/{id}"));
        assertThat(scenario.getSteps().get(0).getWeight(), is(3.0));
        assertThat(scenario.getSteps().get(1).getName(), is("GET /users/{id}"));
        assertThat(scenario.getSteps().get(1).getWeight(), is(2.0));
        assertThat(scenario.getSteps().get(2).getName(), is("GET /products/{id}"));
        assertThat(scenario.getSteps().get(2).getWeight(), is(1.0));
    }

    @Test
    public void verbatimAttachesNoWeightAndUsesDefaultStepSelection() {
        LoadScenario scenario = LoadScenarioFromRecording.generate("rec",
            recorded(request().withMethod("GET").withPath("/a"), request().withMethod("GET").withPath("/b")),
            LoadScenarioFromRecording.Mode.VERBATIM, null, null, null);
        // VERBATIM is unchanged: no weights, default (SEQUENTIAL) step selection.
        assertThat(scenario.getStepSelection(), is(nullValue()));
        for (LoadStep step : scenario.getSteps()) {
            assertThat(step.getWeight(), is(nullValue()));
        }
    }

    @Test
    public void templatizedKeepsDistinctMethodsOnSameRouteSeparate() {
        List<RequestDefinition> recorded = recorded(
            request().withMethod("GET").withPath("/orders/1"),
            request().withMethod("POST").withPath("/orders/2")
        );
        LoadScenario scenario = LoadScenarioFromRecording.generate("rec", recorded, LoadScenarioFromRecording.Mode.TEMPLATIZED, null, null, null);
        assertThat(scenario.getSteps().size(), is(2));
    }

    // --- target override ---

    @Test
    public void targetOverrideIsAppliedToEveryStep() {
        List<RequestDefinition> recorded = recorded(
            request().withMethod("GET").withPath("/a"),
            request().withMethod("GET").withPath("/b")
        );
        LoadScenarioFromRecording.Target target = new LoadScenarioFromRecording.Target("localhost", 1080, "http");
        LoadScenario scenario = LoadScenarioFromRecording.generate("rec", recorded, LoadScenarioFromRecording.Mode.VERBATIM, null, target, null);
        for (LoadStep step : scenario.getSteps()) {
            assertThat(step.getRequest().getFirstHeader("Host"), is("localhost:1080"));
            assertThat(step.getRequest().isSecure(), is(false));
        }
    }

    @Test
    public void targetOverrideReplacesAnExistingRecordedHostHeader() {
        List<RequestDefinition> recorded = recorded(
            request().withMethod("GET").withPath("/a").withHeader("Host", "upstream.example.com")
        );
        LoadScenarioFromRecording.Target target = new LoadScenarioFromRecording.Target("localhost", 1080, "https");
        LoadScenario scenario = LoadScenarioFromRecording.generate("rec", recorded, LoadScenarioFromRecording.Mode.VERBATIM, null, target, null);
        assertThat(scenario.getSteps().get(0).getRequest().getHeader("Host").size(), is(1));
        assertThat(scenario.getSteps().get(0).getRequest().getFirstHeader("Host"), is("localhost:1080"));
        assertThat(scenario.getSteps().get(0).getRequest().isSecure(), is(true));
    }

    @Test
    public void noTargetLeavesRecordedRoutingUntouched() {
        List<RequestDefinition> recorded = recorded(
            request().withMethod("GET").withPath("/a").withHeader("Host", "upstream.example.com")
        );
        LoadScenario scenario = LoadScenarioFromRecording.generate("rec", recorded, LoadScenarioFromRecording.Mode.VERBATIM, null, null, null);
        assertThat(scenario.getSteps().get(0).getRequest().getFirstHeader("Host"), is("upstream.example.com"));
    }

    // --- default profile ---

    @Test
    public void aConservativeDefaultProfileIsAppliedWhenNoneSupplied() {
        LoadScenario scenario = LoadScenarioFromRecording.generate("rec",
            recorded(request().withMethod("GET").withPath("/a")), LoadScenarioFromRecording.Mode.VERBATIM, null, null, null);
        assertThat(scenario.getProfile(), is(notNullValue()));
    }

    @Test
    public void anExplicitProfileIsUsedWhenSupplied() {
        LoadProfile profile = LoadProfile.constant(7, 12_345L);
        LoadScenario scenario = LoadScenarioFromRecording.generate("rec",
            recorded(request().withMethod("GET").withPath("/a")), LoadScenarioFromRecording.Mode.VERBATIM, null, null, profile);
        assertThat(scenario.getProfile(), is(profile));
    }

    // --- empty input ---

    @Test
    public void emptyRecordedInputThrows() {
        try {
            LoadScenarioFromRecording.generate("rec", new ArrayList<>(), LoadScenarioFromRecording.Mode.VERBATIM, null, null, null);
            fail("expected IllegalArgumentException for empty recorded input");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("no recorded requests"));
        }
    }

    @Test
    public void nullRecordedInputThrows() {
        try {
            LoadScenarioFromRecording.generate("rec", null, LoadScenarioFromRecording.Mode.VERBATIM, null, null, null);
            fail("expected IllegalArgumentException for null recorded input");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("no recorded requests"));
        }
    }
}
