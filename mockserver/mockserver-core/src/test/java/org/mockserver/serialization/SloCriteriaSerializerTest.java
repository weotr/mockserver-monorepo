package org.mockserver.serialization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.model.SloCriteriaDTO;
import org.mockserver.slo.Scope;
import org.mockserver.slo.SloCriteria;
import org.mockserver.slo.SloObjective;
import org.mockserver.slo.SloObjectiveResult;
import org.mockserver.slo.SloVerdict;
import org.mockserver.slo.SloWindow;

import java.util.Arrays;
import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

/**
 * Behavioural tests for {@link SloCriteriaSerializer} (lenient parse of the
 * {@code verifySLO} request body and serialization of the verdict response) and
 * for the {@link SloCriteriaDTO} round-trip.
 */
public class SloCriteriaSerializerTest {

    private final SloCriteriaSerializer serializer = new SloCriteriaSerializer(new MockServerLogger());
    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldDeserializeLookbackCriteria() {
        SloCriteria criteria = serializer.deserialize(
            "{" +
                "\"name\":\"checkout-slo\"," +
                "\"window\":{\"type\":\"LOOKBACK\",\"lookbackMillis\":60000}," +
                "\"minimumSampleCount\":5," +
                "\"upstreamHosts\":[\"payments.svc\"]," +
                "\"objectives\":[" +
                "  {\"sli\":\"LATENCY_P95\",\"comparator\":\"LESS_THAN\",\"threshold\":250.0,\"scope\":\"FORWARD\"}," +
                "  {\"sli\":\"ERROR_RATE\",\"comparator\":\"LESS_THAN_OR_EQUAL\",\"threshold\":0.01}" +
                "]}"
        );

        assertThat(criteria.getName(), is("checkout-slo"));
        assertThat(criteria.getWindow().getType(), is(SloWindow.Type.LOOKBACK));
        assertThat(criteria.getWindow().getLookbackMillis(), is(60000L));
        assertThat(criteria.getMinimumSampleCount(), is(5));
        assertThat(criteria.getUpstreamHosts(), contains("payments.svc"));
        assertThat(criteria.getObjectives().size(), is(2));
        SloObjective first = criteria.getObjectives().get(0);
        assertThat(first.getSli(), is(SloObjective.Sli.LATENCY_P95));
        assertThat(first.getComparator(), is(SloObjective.Comparator.LESS_THAN));
        assertThat(first.getThreshold(), is(250.0));
        assertThat(first.getScope(), is(Scope.FORWARD));
        // scope omitted on the second objective defaults to FORWARD
        assertThat(criteria.getObjectives().get(1).getScope(), is(Scope.FORWARD));
    }

    @Test
    public void shouldDeserializeExplicitWindow() {
        SloCriteria criteria = serializer.deserialize(
            "{\"name\":\"w\",\"window\":{\"type\":\"EXPLICIT\",\"fromEpochMillis\":1000,\"toEpochMillis\":2000}," +
                "\"objectives\":[{\"sli\":\"LATENCY_P99\",\"comparator\":\"LESS_THAN\",\"threshold\":1.0}]}"
        );
        assertThat(criteria.getWindow().getType(), is(SloWindow.Type.EXPLICIT));
        assertThat(criteria.getWindow().getFromEpochMillis(), is(1000L));
        assertThat(criteria.getWindow().getToEpochMillis(), is(2000L));
    }

    @Test
    public void shouldRejectBlankBody() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> serializer.deserialize("  "));
        assertThat(e.getMessage(), containsString("an SLO criteria is required"));
    }

    @Test
    public void shouldRejectMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> serializer.deserialize("{not json"));
    }

    @Test
    public void shouldSerializeVerdictWithCleanJson() throws Exception {
        SloVerdict verdict = new SloVerdict()
            .withName("checkout-slo")
            .withResult(SloVerdict.Result.FAIL)
            .withWindowFromEpochMillis(1000L)
            .withWindowToEpochMillis(2000L)
            .withSampleCount(42L)
            .withObjectiveResults(Collections.singletonList(
                new SloObjectiveResult()
                    .withSli(SloObjective.Sli.LATENCY_P95)
                    .withComparator(SloObjective.Comparator.LESS_THAN)
                    .withThreshold(250.0)
                    .withObservedValue(310.0)
                    .withResult(SloVerdict.Result.FAIL)
            ));

        JsonNode json = objectMapper.readTree(serializer.serialize(verdict));

        assertThat(json.get("name").asText(), is("checkout-slo"));
        assertThat(json.get("result").asText(), is("FAIL"));
        assertThat(json.get("windowFromEpochMillis").asLong(), is(1000L));
        assertThat(json.get("windowToEpochMillis").asLong(), is(2000L));
        assertThat(json.get("sampleCount").asLong(), is(42L));
        assertThat(json.get("objectiveResults").size(), is(1));
        JsonNode objective = json.get("objectiveResults").get(0);
        assertThat(objective.get("sli").asText(), is("LATENCY_P95"));
        assertThat(objective.get("observedValue").asDouble(), is(310.0));
        assertThat(objective.get("result").asText(), is("FAIL"));
    }

    @Test
    public void shouldSerializeInconclusiveObjectiveWithNullObservedValue() throws Exception {
        SloVerdict verdict = new SloVerdict()
            .withName("n")
            .withResult(SloVerdict.Result.INCONCLUSIVE)
            .withObjectiveResults(Collections.singletonList(
                new SloObjectiveResult()
                    .withSli(SloObjective.Sli.ERROR_RATE)
                    .withComparator(SloObjective.Comparator.LESS_THAN)
                    .withThreshold(0.01)
                    .withObservedValue(null)
                    .withResult(SloVerdict.Result.INCONCLUSIVE)
                    .withDetail("no requests in window")
            ));

        JsonNode json = objectMapper.readTree(serializer.serialize(verdict));
        JsonNode objective = json.get("objectiveResults").get(0);
        // observedValue is null and omitted by the non-null inclusion writer
        assertThat(objective.get("observedValue"), nullValue());
        assertThat(objective.get("detail").asText(), is("no requests in window"));
    }

    @Test
    public void shouldRoundTripCriteriaThroughDto() {
        SloCriteria original = new SloCriteria()
            .withName("rt")
            .withWindow(SloWindow.lookback(30000))
            .withMinimumSampleCount(7)
            .withUpstreamHosts(new java.util.LinkedHashSet<>(Arrays.asList("a.svc", "b.svc")))
            .withObjectives(
                new SloObjective().withSli(SloObjective.Sli.LATENCY_P50).withComparator(SloObjective.Comparator.LESS_THAN).withThreshold(100).withScope(Scope.FORWARD),
                new SloObjective().withSli(SloObjective.Sli.ERROR_RATE).withComparator(SloObjective.Comparator.GREATER_THAN_OR_EQUAL).withThreshold(0.0).withScope(Scope.INBOUND)
            );

        SloCriteria rebuilt = new SloCriteriaDTO(original).buildObject();

        assertThat(rebuilt.getName(), is("rt"));
        assertThat(rebuilt.getWindow().getType(), is(SloWindow.Type.LOOKBACK));
        assertThat(rebuilt.getWindow().getLookbackMillis(), is(30000L));
        assertThat(rebuilt.getMinimumSampleCount(), is(7));
        assertThat(rebuilt.getUpstreamHosts(), contains("a.svc", "b.svc"));
        assertThat(rebuilt.getObjectives().size(), is(2));
        assertThat(rebuilt.getObjectives().get(0).getSli(), is(SloObjective.Sli.LATENCY_P50));
        assertThat(rebuilt.getObjectives().get(0).getScope(), is(Scope.FORWARD));
        assertThat(rebuilt.getObjectives().get(1).getComparator(), is(SloObjective.Comparator.GREATER_THAN_OR_EQUAL));
        assertThat(rebuilt.getObjectives().get(1).getScope(), is(Scope.INBOUND));
    }

    @Test
    public void shouldDefaultMinimumSampleCountWhenAbsent() {
        SloCriteria criteria = serializer.deserialize(
            "{\"name\":\"d\",\"window\":{\"type\":\"LOOKBACK\",\"lookbackMillis\":1000}," +
                "\"objectives\":[{\"sli\":\"ERROR_RATE\",\"comparator\":\"LESS_THAN\",\"threshold\":1.0}]}"
        );
        // model default applies (1) when the DTO leaves it null
        assertThat(criteria.getMinimumSampleCount(), is(notNullValue()));
        assertThat(criteria.getMinimumSampleCount(), is(1));
    }
}
