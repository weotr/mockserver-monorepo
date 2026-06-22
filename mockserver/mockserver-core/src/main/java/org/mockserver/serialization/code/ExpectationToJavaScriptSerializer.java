package org.mockserver.serialization.code;

import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ExpectationSerializer;

import java.util.List;

/**
 * Generates copy-paste-ready JavaScript/TypeScript expectation code for the
 * MockServer Node.js client ({@code mockserver-client}).
 * <p>
 * Unlike the Java client (which requires the typed builder DSL, hence the
 * {@code ExpectationToJavaSerializer} family), the Node client accepts an
 * expectation as a plain JSON object via {@code mockAnyResponse(...)}. So the
 * "code" for JavaScript is simply each expectation's existing JSON
 * serialization embedded in a client call, preceded by the require preamble.
 * <p>
 * Example output for a single expectation:
 * <pre>
 * const { mockServerClient } = require('mockserver-client');
 *
 * mockServerClient("localhost", 1080).mockAnyResponse({
 *   "httpRequest" : { ... },
 *   "httpResponse" : { ... }
 * });
 * </pre>
 * One {@code mockAnyResponse(...)} call is emitted per expectation.
 *
 * @author jamesdbloom
 */
public class ExpectationToJavaScriptSerializer {

    private static final String NEW_LINE = "\n";

    private final ExpectationSerializer expectationSerializer;

    public ExpectationToJavaScriptSerializer(ExpectationSerializer expectationSerializer) {
        this.expectationSerializer = expectationSerializer;
    }

    public String serialize(List<Expectation> expectations) {
        StringBuilder output = new StringBuilder();
        output.append("const { mockServerClient } = require('mockserver-client');").append(NEW_LINE);
        if (expectations != null) {
            for (Expectation expectation : expectations) {
                if (expectation == null) {
                    continue;
                }
                output.append(NEW_LINE);
                output.append("mockServerClient(\"localhost\", 1080).mockAnyResponse(");
                output.append(expectationSerializer.serialize(expectation));
                output.append(");").append(NEW_LINE);
            }
        }
        return output.toString();
    }
}
