package org.mockserver.load;

import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.ObjectWithJsonToString;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One ordered step of a {@link LoadScenario}: the request to fire and the optional
 * think-time to wait before firing the <em>next</em> step in the same iteration.
 *
 * <p>The request reuses {@link HttpRequest}; per-iteration template placeholders
 * (e.g. {@code $iteration.index}, {@code $uuid}) live in its fields (path, body,
 * headers, query) and are rendered fresh each iteration by the orchestrator using
 * the scenario's {@code templateType}.
 *
 * <p>{@code thinkTime} is inter-step pacing only — the orchestrator schedules the
 * next step after {@code thinkTime.sampleValueMillis()} on its scheduler thread and
 * never calls {@link Delay#applyDelay()} (which would block a worker thread). A null
 * {@code thinkTime} means no pause.
 *
 * <p>Programmatic cross-step capture is deferred; cross-step state in v1 is template-side
 * (e.g. {@code $scenario.set/get} helpers).
 */
public class LoadStep extends ObjectWithJsonToString {

    private HttpRequest request;
    private Delay thinkTime;
    /**
     * Optional human label for this step. When set it is used as the {@code step} metric label
     * (otherwise the step index is used) and may be used as the low-cardinality {@code route} label
     * in place of a templatised request path.
     */
    private String name;
    /**
     * Step-level custom annotation labels. Merged on top of the scenario's labels for this step
     * (step keys win on conflict). See {@link LoadScenario#getLabels()} for how they are exported.
     */
    private Map<String, String> labels;

    public static LoadStep loadStep() {
        return new LoadStep();
    }

    public static LoadStep loadStep(HttpRequest request) {
        return new LoadStep().withRequest(request);
    }

    public HttpRequest getRequest() {
        return request;
    }

    public LoadStep withRequest(HttpRequest request) {
        this.request = request;
        return this;
    }

    public Delay getThinkTime() {
        return thinkTime;
    }

    public LoadStep withThinkTime(Delay thinkTime) {
        this.thinkTime = thinkTime;
        return this;
    }

    /**
     * Optional human label for this step (may be null). See {@link #name}.
     */
    public String getName() {
        return name;
    }

    public LoadStep withName(String name) {
        this.name = name;
        return this;
    }

    /**
     * Step-level custom labels (may be null/empty). See {@link #labels}.
     */
    public Map<String, String> getLabels() {
        return labels;
    }

    public LoadStep withLabels(Map<String, String> labels) {
        this.labels = labels != null ? new LinkedHashMap<>(labels) : null;
        return this;
    }

    public LoadStep withLabel(String name, String value) {
        if (this.labels == null) {
            this.labels = new LinkedHashMap<>();
        }
        this.labels.put(name, value);
        return this;
    }
}
