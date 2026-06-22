package org.mockserver.serialization.code;

import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ExpectationSerializer;

import java.util.List;

/**
 * Generates copy-paste-ready Python expectation code for the MockServer Python
 * client ({@code mockserver}).
 * <p>
 * The Python client accepts an expectation as a dict via
 * {@code Expectation.from_dict(...)} passed to {@code client.upsert(...)}. To
 * keep the embedded payload byte-identical to {@code format=JSON} (and avoid
 * fragile JSON-token-to-Python-literal translation of {@code true}/{@code false}/
 * {@code null}), each expectation's JSON is embedded as a triple-quoted string
 * parsed at runtime with {@code json.loads(...)}.
 * <p>
 * Example output for a single expectation:
 * <pre>
 * import json
 * from mockserver import MockServerClient, Expectation
 *
 * client = MockServerClient("localhost", 1080)
 *
 * client.upsert(Expectation.from_dict(json.loads(JSON)))
 * </pre>
 * One {@code client.upsert(...)} call is emitted per expectation.
 *
 * @author jamesdbloom
 */
public class ExpectationToPythonSerializer {

    private static final String NEW_LINE = "\n";
    private static final String QUOTE = "\"";
    // Python triple-quote delimiter, built by concatenation so the source never contains a
    // three-consecutive-escaped-quote token sequence (which trips the checkstyle Java parser).
    private static final String TRIPLE_QUOTE = QUOTE + QUOTE + QUOTE;

    private final ExpectationSerializer expectationSerializer;

    public ExpectationToPythonSerializer(ExpectationSerializer expectationSerializer) {
        this.expectationSerializer = expectationSerializer;
    }

    public String serialize(List<Expectation> expectations) {
        StringBuilder output = new StringBuilder();
        output.append("import json").append(NEW_LINE);
        output.append("from mockserver import MockServerClient, Expectation").append(NEW_LINE);
        output.append(NEW_LINE);
        output.append("client = MockServerClient(\"localhost\", 1080)").append(NEW_LINE);
        if (expectations != null) {
            for (Expectation expectation : expectations) {
                if (expectation == null) {
                    continue;
                }
                output.append(NEW_LINE);
                output.append("client.upsert(Expectation.from_dict(json.loads(").append(TRIPLE_QUOTE);
                // Safe to embed the JSON verbatim inside the Python triple-quoted literal: Jackson
                // escapes every value double-quote, so the serialized JSON can never contain three
                // consecutive double-quotes (a structural colon, comma, brace or bracket always
                // separates quotes), and the last character is always a closing brace or bracket, so
                // the triple-quoted literal can never be terminated early by any expectation content.
                output.append(expectationSerializer.serialize(expectation));
                output.append(TRIPLE_QUOTE).append(")))").append(NEW_LINE);
            }
        }
        return output.toString();
    }
}
