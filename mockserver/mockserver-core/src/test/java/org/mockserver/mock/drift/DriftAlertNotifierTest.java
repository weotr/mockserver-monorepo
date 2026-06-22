package org.mockserver.mock.drift;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.After;
import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.SocketAddress;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotNull;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class DriftAlertNotifierTest {

    /** Synchronous fake sender capturing every outbound request; returns an immediately-completed 200. */
    private static final class CapturingSender implements Function<HttpRequest, CompletableFuture<HttpResponse>> {
        final List<HttpRequest> captured = new ArrayList<>();

        @Override
        public CompletableFuture<HttpResponse> apply(HttpRequest request) {
            captured.add(request);
            return CompletableFuture.completedFuture(response().withStatusCode(200));
        }
    }

    @After
    public void cleanSingleton() {
        // Leave the process-wide singleton clean for the next sequential test.
        DriftAlertNotifier.getInstance().reset();
        DriftAlertNotifier.getInstance().setSender(null);
        DriftAlertNotifier.getInstance().setClock(System::currentTimeMillis);
        DriftAlertNotifier.getInstance().configure(false, "", SemanticSeverity.BREAKING, 60000);
    }

    private static DriftRecord record(DriftType type, String field) {
        return new DriftRecord()
            .setExpectationId("exp-1")
            .setDriftType(type)
            .setField(field)
            .setExpectedValue("200")
            .setActualValue("500")
            .setConfidence(1.0)
            .setEpochTimeMs(123L);
    }

    @Test
    public void breakingDriftFiresCorrectPayload() throws Exception {
        DriftAlertNotifier notifier = new DriftAlertNotifier(() -> 1000L);
        CapturingSender sender = new CapturingSender();
        notifier.setSender(sender);
        notifier.configure(true, "https://hooks.example.com:8443/drift?x=1", SemanticSeverity.BREAKING, 60000);

        DriftRecord drift = record(DriftType.STATUS, "statusCode");
        notifier.onDriftStored(drift);

        assertThat(sender.captured, hasSize(1));
        HttpRequest out = sender.captured.get(0);
        assertThat(out.getMethod().getValue(), is("POST"));
        assertThat(out.getPath().getValue(), is("/drift?x=1"));
        assertThat(out.getFirstHeader("Content-Type"), containsString("application/json"));
        SocketAddress addr = out.getSocketAddress();
        assertNotNull(addr);
        assertThat(addr.getHost(), is("hooks.example.com"));
        assertThat(addr.getPort(), is(8443));
        assertThat(addr.getScheme(), is(SocketAddress.Scheme.HTTPS));
        assertThat(out.isSecure(), is(true));

        JsonNode envelope = ObjectMapperFactory.createObjectMapper().readTree(out.getBodyAsString());
        assertThat(envelope.get("event").asText(), is("mockserver.drift.alert"));
        assertThat(envelope.get("epochTimeMs").asLong(), is(1000L));
        assertThat(envelope.get("severity").asText(), is("BREAKING"));
        assertThat(envelope.get("drift").get("expectationId").asText(), is("exp-1"));
        assertThat(envelope.get("drift").get("driftType").asText(), is("STATUS"));
        assertThat(envelope.get("drift").get("field").asText(), is("statusCode"));
    }

    @Test
    public void belowThresholdDoesNotFire() {
        DriftAlertNotifier notifier = new DriftAlertNotifier(() -> 1000L);
        CapturingSender sender = new CapturingSender();
        notifier.setSender(sender);
        // Threshold BREAKING; an INFORMATIONAL structural drift (SCHEMA_FIELD_ADDED) must not fire.
        notifier.configure(true, "http://h/x", SemanticSeverity.BREAKING, 60000);

        notifier.onDriftStored(record(DriftType.SCHEMA_FIELD_ADDED, "a.b"));

        assertThat(sender.captured, hasSize(0));
    }

    @Test
    public void warningThresholdFiresWarningButNotInformational() {
        DriftAlertNotifier notifier = new DriftAlertNotifier(() -> 1000L);
        CapturingSender sender = new CapturingSender();
        notifier.setSender(sender);
        notifier.configure(true, "http://h/x", SemanticSeverity.WARNING, 60000);

        notifier.onDriftStored(record(DriftType.HEADER_CHANGED, "header.x"));   // WARNING -> fires
        notifier.onDriftStored(record(DriftType.SCHEMA_FIELD_ADDED, "a.b"));    // INFORMATIONAL -> no

        assertThat(sender.captured, hasSize(1));
    }

    @Test
    public void semanticSeverityOverridesStructuralFallback() {
        DriftAlertNotifier notifier = new DriftAlertNotifier(() -> 1000L);
        CapturingSender sender = new CapturingSender();
        notifier.setSender(sender);
        notifier.configure(true, "http://h/x", SemanticSeverity.BREAKING, 60000);

        // HEADER_ADDED structurally falls back to INFORMATIONAL (would NOT fire at BREAKING threshold),
        // but an explicit semantic BREAKING severity must override and fire.
        DriftRecord drift = record(DriftType.HEADER_ADDED, "header.y")
            .setSemanticSeverity(SemanticSeverity.BREAKING);
        notifier.onDriftStored(drift);

        assertThat(sender.captured, hasSize(1));
        assertThat(sender.captured.get(0).getBodyAsString(), containsString("\"severity\":\"BREAKING\""));
    }

    @Test
    public void failSoftWhenSenderReturnsExceptionallyCompletedFuture() {
        DriftAlertNotifier notifier = new DriftAlertNotifier(() -> 1000L);
        notifier.setSender(req -> {
            CompletableFuture<HttpResponse> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("boom"));
            return f;
        });
        notifier.configure(true, "http://h/x", SemanticSeverity.BREAKING, 60000);

        // Must not throw.
        notifier.onDriftStored(record(DriftType.STATUS, "statusCode"));
    }

    @Test
    public void failSoftWhenSenderThrowsSynchronously() {
        DriftAlertNotifier notifier = new DriftAlertNotifier(() -> 1000L);
        notifier.setSender(req -> {
            throw new RuntimeException("synchronous boom");
        });
        notifier.configure(true, "http://h/x", SemanticSeverity.BREAKING, 60000);

        // Must not throw into the caller.
        notifier.onDriftStored(record(DriftType.STATUS, "statusCode"));
    }

    @Test
    public void noOpWhenDisabledOrUnwired() {
        DriftAlertNotifier notifier = new DriftAlertNotifier(() -> 1000L);
        CapturingSender sender = new CapturingSender();

        // disabled
        notifier.setSender(sender);
        notifier.configure(false, "http://h/x", SemanticSeverity.BREAKING, 60000);
        notifier.onDriftStored(record(DriftType.STATUS, "statusCode"));
        assertThat(sender.captured, hasSize(0));

        // enabled but null sender
        notifier.setSender(null);
        notifier.configure(true, "http://h/x", SemanticSeverity.BREAKING, 60000);
        notifier.onDriftStored(record(DriftType.STATUS, "statusCode"));
        assertThat(sender.captured, hasSize(0));

        // enabled, sender present, but empty URL
        notifier.setSender(sender);
        notifier.configure(true, "", SemanticSeverity.BREAKING, 60000);
        notifier.onDriftStored(record(DriftType.STATUS, "statusCode"));
        assertThat(sender.captured, hasSize(0));
    }

    @Test
    public void cooldownDeDupesSameSignatureAndAdvancingClockReleasesIt() {
        AtomicLong now = new AtomicLong(1000L);
        DriftAlertNotifier notifier = new DriftAlertNotifier(now::get);
        CapturingSender sender = new CapturingSender();
        notifier.setSender(sender);
        notifier.configure(true, "http://h/x", SemanticSeverity.BREAKING, 60000);

        // Same signature within cooldown => only one send.
        notifier.onDriftStored(record(DriftType.STATUS, "statusCode"));
        now.set(1000L + 30000L);
        notifier.onDriftStored(record(DriftType.STATUS, "statusCode"));
        assertThat(sender.captured, hasSize(1));

        // Advance beyond the cooldown window => second send.
        now.set(1000L + 70000L);
        notifier.onDriftStored(record(DriftType.STATUS, "statusCode"));
        assertThat(sender.captured, hasSize(2));
    }

    @Test
    public void differentSignaturesEachFire() {
        DriftAlertNotifier notifier = new DriftAlertNotifier(() -> 1000L);
        CapturingSender sender = new CapturingSender();
        notifier.setSender(sender);
        notifier.configure(true, "http://h/x", SemanticSeverity.BREAKING, 60000);

        notifier.onDriftStored(record(DriftType.STATUS, "statusCode"));
        notifier.onDriftStored(record(DriftType.SCHEMA_FIELD_REMOVED, "a.b"));

        assertThat(sender.captured, hasSize(2));
    }

    @Test
    public void resetClearsCooldownButNotConfigOrSender() {
        AtomicLong now = new AtomicLong(1000L);
        DriftAlertNotifier notifier = new DriftAlertNotifier(now::get);
        CapturingSender sender = new CapturingSender();
        notifier.setSender(sender);
        notifier.configure(true, "http://h/x", SemanticSeverity.BREAKING, 60000);

        notifier.onDriftStored(record(DriftType.STATUS, "statusCode"));
        assertThat(sender.captured, hasSize(1));

        // Within cooldown but after reset => fires again (cooldown cleared); config + sender intact.
        notifier.reset();
        notifier.onDriftStored(record(DriftType.STATUS, "statusCode"));
        assertThat(sender.captured, hasSize(2));
    }

    @Test
    public void endToEndThroughDriftAnalyseFiresViaSingleton() {
        // Proves the DriftAnalyzer.analyse store loop calls DriftAlertNotifier.getInstance().onDriftStored.
        CapturingSender sender = new CapturingSender();
        DriftAlertNotifier.getInstance().setSender(sender);
        DriftAlertNotifier.getInstance().setClock(() -> 5000L);
        DriftAlertNotifier.getInstance().configure(true, "http://hook/endpoint", SemanticSeverity.BREAKING, 60000);

        DriftStore localStore = new DriftStore(100);
        DriftAnalyzer analyzer = new DriftAnalyzer(localStore);
        Expectation expectation = new Expectation(request().withPath("/api"))
            .thenRespond(response().withStatusCode(200));
        // Status drift -> BREAKING -> fires.
        analyzer.analyse(expectation, response().withStatusCode(500));

        assertThat(sender.captured, hasSize(1));
        assertThat(sender.captured.get(0).getBodyAsString(), containsString("\"event\":\"mockserver.drift.alert\""));
    }

    @Test
    public void effectiveSeverityStructuralFallbackCoversAllDriftTypes() {
        assertThat(DriftAlertNotifier.effectiveSeverity(record(DriftType.STATUS, "f")), is(SemanticSeverity.BREAKING));
        assertThat(DriftAlertNotifier.effectiveSeverity(record(DriftType.SCHEMA_FIELD_REMOVED, "f")), is(SemanticSeverity.BREAKING));
        assertThat(DriftAlertNotifier.effectiveSeverity(record(DriftType.SCHEMA_TYPE_CHANGED, "f")), is(SemanticSeverity.BREAKING));
        assertThat(DriftAlertNotifier.effectiveSeverity(record(DriftType.HEADER_REMOVED, "f")), is(SemanticSeverity.WARNING));
        assertThat(DriftAlertNotifier.effectiveSeverity(record(DriftType.HEADER_CHANGED, "f")), is(SemanticSeverity.WARNING));
        assertThat(DriftAlertNotifier.effectiveSeverity(record(DriftType.PERFORMANCE, "f")), is(SemanticSeverity.WARNING));
        assertThat(DriftAlertNotifier.effectiveSeverity(record(DriftType.SCHEMA_FIELD_ADDED, "f")), is(SemanticSeverity.INFORMATIONAL));
        assertThat(DriftAlertNotifier.effectiveSeverity(record(DriftType.HEADER_ADDED, "f")), is(SemanticSeverity.INFORMATIONAL));
    }

    @Test
    public void malformedUrlDoesNotThrowAndDoesNotFire() {
        DriftAlertNotifier notifier = new DriftAlertNotifier(() -> 1000L);
        CapturingSender sender = new CapturingSender();
        notifier.setSender(sender);
        // No scheme/host -> outbound build returns null -> no send, no throw.
        notifier.configure(true, "not a url", SemanticSeverity.BREAKING, 60000);
        notifier.onDriftStored(record(DriftType.STATUS, "statusCode"));
        assertThat(sender.captured, hasSize(0));
    }

    @Test
    public void nullRecordIsNoOp() {
        DriftAlertNotifier notifier = new DriftAlertNotifier(() -> 1000L);
        CapturingSender sender = new CapturingSender();
        notifier.setSender(sender);
        notifier.configure(true, "http://h/x", SemanticSeverity.BREAKING, 60000);
        notifier.onDriftStored(null);
        assertThat(sender.captured, hasSize(0));
    }
}
