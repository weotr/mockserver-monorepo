package org.mockserver.mock.diff;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class BaselineDifferTest {

    private final BaselineDiffer differ = new BaselineDiffer();

    private Expectation interaction(String method, String path, int status, String body) {
        return when(request().withMethod(method).withPath(path))
            .thenRespond(response().withStatusCode(status).withBody(body));
    }

    @Test
    public void shouldReportNoDriftForIdenticalSets() {
        List<Expectation> baseline = Collections.singletonList(interaction("GET", "/api/users", 200, "{\"id\":1}"));
        List<Expectation> current = Collections.singletonList(interaction("GET", "/api/users", 200, "{\"id\":1}"));

        BaselineDiffReport report = differ.diffExpectations(baseline, current);

        assertThat(report.hasDrift(), is(false));
        assertThat(report.getAdded(), is(empty()));
        assertThat(report.getRemoved(), is(empty()));
        assertThat(report.getChanged(), is(empty()));
    }

    @Test
    public void shouldReportAddedRequest() {
        List<Expectation> baseline = Collections.singletonList(interaction("GET", "/api/users", 200, "{}"));
        List<Expectation> current = Arrays.asList(
            interaction("GET", "/api/users", 200, "{}"),
            interaction("GET", "/api/orders", 200, "{}"));

        BaselineDiffReport report = differ.diffExpectations(baseline, current);

        assertThat(report.hasDrift(), is(true));
        assertThat(report.getAdded(), hasSize(1));
        assertThat(report.getAdded().get(0).getKey(), is("GET /api/orders"));
        assertThat(report.getRemoved(), is(empty()));
        assertThat(report.getChanged(), is(empty()));
    }

    @Test
    public void shouldReportRemovedRequest() {
        List<Expectation> baseline = Arrays.asList(
            interaction("GET", "/api/users", 200, "{}"),
            interaction("DELETE", "/api/users/1", 204, ""));
        List<Expectation> current = Collections.singletonList(interaction("GET", "/api/users", 200, "{}"));

        BaselineDiffReport report = differ.diffExpectations(baseline, current);

        assertThat(report.hasDrift(), is(true));
        assertThat(report.getRemoved(), hasSize(1));
        assertThat(report.getRemoved().get(0).getKey(), is("DELETE /api/users/1"));
        assertThat(report.getAdded(), is(empty()));
        assertThat(report.getChanged(), is(empty()));
    }

    @Test
    public void shouldReportChangedWhenResponseGainsJsonField() {
        List<Expectation> baseline = Collections.singletonList(
            interaction("GET", "/api/users", 200, "{\"id\":1}"));
        List<Expectation> current = Collections.singletonList(
            interaction("GET", "/api/users", 200, "{\"id\":1,\"email\":\"a@b.com\"}"));

        BaselineDiffReport report = differ.diffExpectations(baseline, current);

        assertThat(report.hasDrift(), is(true));
        assertThat(report.getChanged(), hasSize(1));
        InteractionDiff changed = report.getChanged().get(0);
        assertThat(changed.getKey(), is("GET /api/users"));
        assertThat(changed.getResponseDiffs(), hasSize(1));
        FieldDiff fieldDiff = changed.getResponseDiffs().get(0);
        assertThat(fieldDiff.getDiffType(), is(FieldDiff.DiffType.ADDED));
        assertThat(fieldDiff.getField(), is("response.body.email"));
    }

    @Test
    public void shouldReportChangedWhenResponseLosesJsonField() {
        List<Expectation> baseline = Collections.singletonList(
            interaction("GET", "/api/users", 200, "{\"id\":1,\"name\":\"bob\"}"));
        List<Expectation> current = Collections.singletonList(
            interaction("GET", "/api/users", 200, "{\"id\":1}"));

        BaselineDiffReport report = differ.diffExpectations(baseline, current);

        assertThat(report.getChanged(), hasSize(1));
        FieldDiff fieldDiff = report.getChanged().get(0).getResponseDiffs().get(0);
        assertThat(fieldDiff.getDiffType(), is(FieldDiff.DiffType.REMOVED));
        assertThat(fieldDiff.getField(), is("response.body.name"));
    }

    @Test
    public void shouldReportChangedWhenJsonFieldTypeChanges() {
        List<Expectation> baseline = Collections.singletonList(
            interaction("GET", "/api/users", 200, "{\"id\":1}"));
        List<Expectation> current = Collections.singletonList(
            interaction("GET", "/api/users", 200, "{\"id\":\"one\"}"));

        BaselineDiffReport report = differ.diffExpectations(baseline, current);

        assertThat(report.getChanged(), hasSize(1));
        FieldDiff fieldDiff = report.getChanged().get(0).getResponseDiffs().get(0);
        assertThat(fieldDiff.getDiffType(), is(FieldDiff.DiffType.CHANGED));
        assertThat(fieldDiff.getField(), is("response.body.id"));
        assertThat(fieldDiff.getExpectedValue(), is("number"));
        assertThat(fieldDiff.getActualValue(), is("string"));
    }

    @Test
    public void shouldNotReportDriftWhenOnlyJsonFieldValueChanges() {
        // same shape (same fields, same types) but different scalar VALUES — structural, value-insensitive
        List<Expectation> baseline = Collections.singletonList(
            interaction("GET", "/api/users", 200, "{\"id\":1,\"name\":\"alice\"}"));
        List<Expectation> current = Collections.singletonList(
            interaction("GET", "/api/users", 200, "{\"id\":999,\"name\":\"bob\"}"));

        BaselineDiffReport report = differ.diffExpectations(baseline, current);

        assertThat(report.hasDrift(), is(false));
        assertThat(report.getChanged(), is(empty()));
    }

    @Test
    public void shouldReportChangedWhenResponseStatusCodeChanges() {
        List<Expectation> baseline = Collections.singletonList(interaction("GET", "/api/users", 200, "{}"));
        List<Expectation> current = Collections.singletonList(interaction("GET", "/api/users", 500, "{}"));

        BaselineDiffReport report = differ.diffExpectations(baseline, current);

        assertThat(report.getChanged(), hasSize(1));
        FieldDiff fieldDiff = report.getChanged().get(0).getResponseDiffs().get(0);
        assertThat(fieldDiff.getField(), is("response.statusCode"));
        assertThat(fieldDiff.getDiffType(), is(FieldDiff.DiffType.CHANGED));
        assertThat(fieldDiff.getExpectedValue(), is("200"));
        assertThat(fieldDiff.getActualValue(), is("500"));
    }

    @Test
    public void shouldMatchRequestsAcrossSetsIgnoringTrailingSlash() {
        List<Expectation> baseline = Collections.singletonList(interaction("GET", "/api/users/", 200, "{}"));
        List<Expectation> current = Collections.singletonList(interaction("GET", "/api/users", 200, "{}"));

        BaselineDiffReport report = differ.diffExpectations(baseline, current);

        assertThat(report.hasDrift(), is(false));
    }

    @Test
    public void shouldReportNewResponseArrayElementAsDrift() {
        List<Expectation> baseline = Collections.singletonList(
            interaction("GET", "/api/users", 200, "[{\"id\":1}]"));
        List<Expectation> current = Collections.singletonList(
            interaction("GET", "/api/users", 200, "[{\"id\":1},{\"id\":2}]"));

        BaselineDiffReport report = differ.diffExpectations(baseline, current);

        assertThat(report.getChanged(), hasSize(1));
        FieldDiff fieldDiff = report.getChanged().get(0).getResponseDiffs().get(0);
        assertThat(fieldDiff.getDiffType(), is(FieldDiff.DiffType.ADDED));
        assertThat(fieldDiff.getField(), is("response.body[1]"));
    }

    @Test
    public void shouldDiffRawInteractionsAndDetectAddedHeader() {
        BaselineDiffer.Interaction baseline = new BaselineDiffer.Interaction(
            request().withMethod("GET").withPath("/x"),
            response().withStatusCode(200));
        BaselineDiffer.Interaction current = new BaselineDiffer.Interaction(
            request().withMethod("GET").withPath("/x"),
            response().withStatusCode(200).withHeader("X-Trace", "abc"));

        BaselineDiffReport report = differ.diff(
            Collections.singletonList(baseline), Collections.singletonList(current));

        assertThat(report.getChanged(), hasSize(1));
        FieldDiff fieldDiff = report.getChanged().get(0).getResponseDiffs().get(0);
        assertThat(fieldDiff.getField(), is("response.header.x-trace"));
        assertThat(fieldDiff.getDiffType(), is(FieldDiff.DiffType.ADDED));
    }
}
