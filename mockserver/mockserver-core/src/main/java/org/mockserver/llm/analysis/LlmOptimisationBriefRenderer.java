package org.mockserver.llm.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.llm.ProviderCodec;
import org.mockserver.llm.ProviderCodecRegistry;
import org.mockserver.llm.analysis.LlmOptimisationReportBuilder.CapturedExchange;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Provider;
import org.mockserver.model.ToolUse;
import org.mockserver.serialization.ObjectMapperFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Renders an {@link LlmOptimisationReport} to a copy-paste Markdown
 * "optimisation brief" — bundle (A) of the LLM optimisation export feature.
 * <p>
 * The brief is self-contained: a user can paste it into any LLM and get
 * cost-reduction advice with no extra context. Section order is the FROZEN
 * contract (§4): framing preamble, run summary, per-call table, detected
 * opportunities (HIGH first), then a conversations &amp; tool-definitions
 * appendix.
 * <p>
 * The appendix is rendered from the captured requests <em>after</em> redaction
 * via {@link FixtureRedactor}, so Authorization / api-key / cookie values and
 * any configured body fields never appear in the brief.
 */
public class LlmOptimisationBriefRenderer {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();

    static final String FRAMING_PREAMBLE =
        "You are an LLM cost-optimisation expert. Below is a captured agent run that was proxied "
            + "through MockServer. Identify concrete ways to reduce inference cost by (1) tightening or "
            + "caching prompts, (2) moving deterministic logic out of the model into tools, and (3) moving "
            + "logic into HTTP or MCP endpoints. For each opportunity, estimate the token and cost saving "
            + "and show the change. Prioritise the highest-saving, lowest-risk changes first.";

    /**
     * Render the brief for a report whose appendix is drawn from the given
     * exchanges. The exchanges are redacted with {@code redactor} before any of
     * their content is emitted.
     *
     * @param report    the assembled report (already deterministic)
     * @param exchanges the captured exchanges backing the appendix (may be null/empty)
     * @param redactor  the redactor applied to request bodies before rendering
     */
    public String render(LlmOptimisationReport report, List<CapturedExchange> exchanges, FixtureRedactor redactor) {
        StringBuilder md = new StringBuilder();

        boolean empty = report == null || report.getTotals() == null || report.getTotals().getCallCount() == 0;

        // 1. Framing preamble
        md.append("> ").append(FRAMING_PREAMBLE).append("\n\n");

        if (empty) {
            md.append("## Run summary\n\n");
            md.append("No LLM traffic captured for this session.\n");
            return md.toString();
        }

        // 2. Run summary
        appendRunSummary(md, report);

        // 3. Per-call table
        appendPerCallTable(md, report);

        // 4. Detected opportunities (signals already HIGH-first from the builder)
        appendOpportunities(md, report);

        // 5. Conversations & tool definitions appendix (redacted)
        appendAppendix(md, report, exchanges, redactor);

        return md.toString();
    }

    private void appendRunSummary(StringBuilder md, LlmOptimisationReport report) {
        LlmOptimisationReport.Totals t = report.getTotals();
        LlmOptimisationReport.Session s = report.getSession();
        md.append("## Run summary\n\n");
        md.append("- Provider(s): ").append(joinOrNone(s.getProviders())).append("\n");
        md.append("- Model(s): ").append(joinOrNone(s.getModels())).append("\n");
        md.append("- Calls: ").append(t.getCallCount()).append("\n");
        md.append("- Input tokens: ").append(formatTokens(t.getInputTokens())).append("\n");
        md.append("- Output tokens: ").append(formatTokens(t.getOutputTokens())).append("\n");
        md.append("- Cached input tokens: ").append(formatTokens(t.getCachedInputTokens())).append("\n");
        md.append("- Reasoning tokens: ").append(formatTokens(t.getReasoningTokens())).append("\n");
        md.append("- Estimated cost: ").append(formatUsd(t.getEstimatedCostUsd()))
            .append(t.isCostIsEstimated() ? " (estimated — usage not reported upstream)" : "").append("\n");
        md.append("- Total latency: ").append(t.getTotalLatencyMs()).append(" ms\n");
        md.append("- Tool calls: ").append(t.getToolCallCount()).append("\n\n");
    }

    private void appendPerCallTable(StringBuilder md, LlmOptimisationReport report) {
        md.append("## Per-call breakdown\n\n");
        md.append("| # | model | in tok | out tok | cost | latency | tools | finish |\n");
        md.append("|---|-------|--------|---------|------|---------|-------|--------|\n");
        for (LlmOptimisationReport.Call call : report.getCalls()) {
            md.append("| ").append(call.getIndex())
                .append(" | ").append(nullToDash(call.getModel()))
                .append(" | ").append(formatTokens(call.getInputTokens()))
                .append(" | ").append(formatTokens(call.getOutputTokens()))
                .append(" | ").append(formatUsd(call.getEstimatedCostUsd())).append(call.isCostIsEstimated() ? "*" : "")
                .append(" | ").append(call.getLatencyMs()).append(" ms")
                .append(" | ").append(call.getToolCalls().size())
                .append(" | ").append(nullToDash(call.getFinishReason()))
                .append(" |\n");
        }
        md.append("\n");
        md.append("*estimated cost (usage not reported upstream).\n\n");
    }

    private void appendOpportunities(StringBuilder md, LlmOptimisationReport report) {
        md.append("## Detected opportunities\n\n");
        if (report.getSignals().isEmpty()) {
            md.append("No deterministic optimisation signals were detected.\n\n");
            return;
        }
        for (LlmOptimisationReport.Signal signal : report.getSignals()) {
            md.append("### [").append(signal.getSeverity()).append("] ").append(signal.getTitle()).append("\n\n");
            md.append(signal.getDetail()).append("\n\n");
            md.append("- Affected calls: ").append(formatIntList(signal.getAffectedCalls())).append("\n");
            if (signal.getEstimatedWastedInputTokens() != null) {
                md.append("- Estimated wasted input tokens: ")
                    .append(formatTokens(signal.getEstimatedWastedInputTokens())).append("\n");
            }
            if (signal.getEstimatedSavingUsd() != null) {
                md.append("- Estimated saving: ").append(formatUsd(signal.getEstimatedSavingUsd())).append("\n");
            }
            md.append("- Recommendation: ").append(signal.getRecommendation()).append("\n\n");
        }
    }

    private void appendAppendix(StringBuilder md, LlmOptimisationReport report,
                                List<CapturedExchange> exchanges, FixtureRedactor redactor) {
        md.append("## Conversations & tool definitions (appendix)\n\n");
        if (exchanges == null || exchanges.isEmpty()) {
            md.append("No request bodies were available to include.\n");
            return;
        }
        FixtureRedactor effectiveRedactor = redactor != null ? redactor : new FixtureRedactor();
        int index = 0;
        for (CapturedExchange exchange : exchanges) {
            HttpRequest request = exchange.getRequest();
            if (request == null) {
                continue;
            }
            Optional<Provider> providerOpt = org.mockserver.llm.client.LlmProviderSniffer.sniff(request);
            if (!providerOpt.isPresent()) {
                continue; // mirror the builder: only LLM traffic is included
            }
            Provider provider = providerOpt.get();
            HttpRequest redacted = redactRequest(effectiveRedactor, request);

            md.append("### Call ").append(index).append("\n\n");
            appendMessages(md, redacted, provider);
            appendToolDefinitions(md, redacted);
            index++;
        }
    }

    /**
     * Redact the request's sensitive headers and configured body fields via
     * {@link FixtureRedactor}, returning the redacted {@link HttpRequest}.
     * Falls back to the original request only if redaction produced a non-HTTP
     * request definition (it never does for an {@link HttpRequest} input).
     */
    private static HttpRequest redactRequest(FixtureRedactor redactor, HttpRequest request) {
        org.mockserver.mock.Expectation[] redacted = redactor.redact(
            new org.mockserver.mock.Expectation[]{new org.mockserver.mock.Expectation(request)});
        org.mockserver.model.RequestDefinition def = redacted[0].getHttpRequest();
        return def instanceof HttpRequest ? (HttpRequest) def : request;
    }

    private void appendMessages(StringBuilder md, HttpRequest redactedRequest, Provider provider) {
        Optional<ProviderCodec> codecOpt = ProviderCodecRegistry.getInstance().lookup(provider);
        if (!codecOpt.isPresent()) {
            return;
        }
        ParsedConversation conversation;
        try {
            conversation = codecOpt.get().decode(redactedRequest);
        } catch (Exception e) {
            conversation = null;
        }
        if (conversation == null || conversation.getMessages().isEmpty()) {
            md.append("_(no decodable messages)_\n\n");
            return;
        }
        md.append("**Messages:**\n\n");
        for (ParsedMessage message : conversation.getMessages()) {
            md.append("- **").append(message.getRole().name()).append("**: ");
            String text = message.getTextContent();
            md.append(text != null ? singleLine(text) : "");
            if (!message.getToolCalls().isEmpty()) {
                md.append(" _(tool calls: ");
                List<String> names = new ArrayList<>();
                for (ToolUse tc : message.getToolCalls()) {
                    names.add(tc.getName());
                }
                md.append(String.join(", ", names)).append(")_");
            }
            md.append("\n");
        }
        md.append("\n");
    }

    private void appendToolDefinitions(StringBuilder md, HttpRequest redactedRequest) {
        if (redactedRequest.getBody() == null) {
            return;
        }
        try {
            JsonNode body = OBJECT_MAPPER.readTree(redactedRequest.getBodyAsString());
            JsonNode tools = body.path("tools");
            if (tools.isArray() && tools.size() > 0) {
                md.append("**Tool definitions:**\n\n```json\n");
                md.append(OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(tools));
                md.append("\n```\n\n");
            }
        } catch (Exception e) {
            // not JSON or no tools — skip
        }
    }

    // --- formatting helpers ---

    private static String joinOrNone(List<String> values) {
        return values == null || values.isEmpty() ? "(none)" : String.join(", ", values);
    }

    private static String formatTokens(long tokens) {
        return String.format("%,d", tokens);
    }

    private static String formatUsd(double usd) {
        return String.format("$%.4f", usd);
    }

    private static String formatIntList(List<Integer> values) {
        if (values == null || values.isEmpty()) {
            return "(none)";
        }
        List<String> parts = new ArrayList<>();
        for (Integer v : values) {
            parts.add(String.valueOf(v));
        }
        return String.join(", ", parts);
    }

    private static String nullToDash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String singleLine(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
