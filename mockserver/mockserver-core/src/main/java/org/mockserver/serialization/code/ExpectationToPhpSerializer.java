package org.mockserver.serialization.code;

import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ExpectationSerializer;

import java.util.List;

/**
 * Generates copy-paste-ready PHP expectation code for the MockServer PHP client
 * ({@code mock-server/mockserver-client}).
 * <p>
 * Following the same JSON-wrap pattern as the JavaScript/Python generators, the
 * generated code embeds each expectation's existing JSON serialization (the
 * same bytes produced by {@code format=JSON}) in a PHP nowdoc, decodes it with
 * {@code json_decode(..., true)}, reconstructs it via
 * {@code Expectation::fromArray(...)} and passes it to
 * {@code $client->upsertExpectation(...)}.
 * <p>
 * Verified against the PHP client source:
 * <ul>
 *   <li>{@code new MockServerClient('localhost', 1080)} ({@code MockServer\MockServerClient})</li>
 *   <li>{@code public function upsertExpectation(Expectation $expectation): array}</li>
 *   <li>{@code Expectation::fromArray(array $data): self} (added alongside this generator)</li>
 * </ul>
 * <p>
 * The JSON is embedded in a PHP nowdoc ({@code <<<'JSON' ... JSON}). A nowdoc
 * performs no variable interpolation or escape processing, so the JSON is
 * preserved verbatim. The closing delimiter is the literal token {@code JSON}
 * alone on a line; Jackson's serialized JSON can never produce such a line
 * (every line is part of structural JSON whose string values are escaped and
 * quoted), so the nowdoc cannot be terminated early by expectation content.
 * Example output:
 * <pre>
 * &lt;?php
 *
 * use MockServer\MockServerClient;
 * use MockServer\Expectation;
 *
 * $client = new MockServerClient('localhost', 1080);
 *
 * $client-&gt;upsertExpectation(Expectation::fromArray(json_decode(&lt;&lt;&lt;'JSON'
 * { ... }
 * JSON, true)));
 * </pre>
 *
 * @author jamesdbloom
 */
public class ExpectationToPhpSerializer {

    private static final String NEW_LINE = "\n";
    private static final String NOWDOC_DELIMITER = "JSON";

    private final ExpectationSerializer expectationSerializer;

    public ExpectationToPhpSerializer(ExpectationSerializer expectationSerializer) {
        this.expectationSerializer = expectationSerializer;
    }

    public String serialize(List<Expectation> expectations) {
        StringBuilder output = new StringBuilder();
        output.append("<?php").append(NEW_LINE);
        output.append(NEW_LINE);
        output.append("use MockServer\\MockServerClient;").append(NEW_LINE);
        output.append("use MockServer\\Expectation;").append(NEW_LINE);
        output.append(NEW_LINE);
        output.append("$client = new MockServerClient('localhost', 1080);").append(NEW_LINE);
        if (expectations != null) {
            for (Expectation expectation : expectations) {
                if (expectation == null) {
                    continue;
                }
                output.append(NEW_LINE);
                output.append("$client->upsertExpectation(Expectation::fromArray(json_decode(<<<'").append(NOWDOC_DELIMITER).append("'").append(NEW_LINE);
                output.append(expectationSerializer.serialize(expectation)).append(NEW_LINE);
                output.append(NOWDOC_DELIMITER).append(", true)));").append(NEW_LINE);
            }
        }
        return output.toString();
    }
}
