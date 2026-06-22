package org.mockserver.serialization.code;

import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ExpectationSerializer;

import java.util.List;

/**
 * Generates copy-paste-ready C# expectation code for the MockServer .NET client
 * ({@code MockServer.Client}).
 * <p>
 * Following the same JSON-wrap pattern as the JavaScript/Python generators, the
 * generated code embeds each expectation's existing JSON serialization (the
 * same bytes produced by {@code format=JSON}) in a C# verbatim string and
 * deserializes it with {@code JsonSerializer.Deserialize<Expectation>(...)},
 * then passes it to {@code client.Upsert(...)}.
 * <p>
 * Verified against the .NET client source:
 * <ul>
 *   <li>{@code new MockServerClient("localhost", 1080)} ({@code MockServer.Client})</li>
 *   <li>{@code public List<Expectation> Upsert(params Expectation[] expectations)}</li>
 *   <li>{@code MockServer.Client.Models.Expectation} (deserialised with System.Text.Json)</li>
 * </ul>
 * The client's own {@code JsonSerializerOptions} ({@code CamelCase} +
 * {@code WhenWritingNull}) are {@code private}, so the generated code rebuilds
 * an equivalent options object to deserialise with.
 * <p>
 * The JSON is embedded in a C# verbatim string ({@code @"..."}); inside a
 * verbatim string the only character needing escaping is the double-quote,
 * which is doubled ({@code ""}). Backslashes and newlines are taken literally,
 * matching the JSON bytes.
 * Example output:
 * <pre>
 * using System.Text.Json;
 * using System.Text.Json.Serialization;
 * using MockServer.Client;
 * using MockServer.Client.Models;
 *
 * var jsonOptions = new JsonSerializerOptions
 * {
 *     PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
 *     DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull
 * };
 *
 * var client = new MockServerClient("localhost", 1080);
 *
 * client.Upsert(JsonSerializer.Deserialize&lt;Expectation&gt;(@"{ ... }", jsonOptions));
 * </pre>
 *
 * @author jamesdbloom
 */
public class ExpectationToCSharpSerializer {

    private static final String NEW_LINE = "\n";

    private final ExpectationSerializer expectationSerializer;

    public ExpectationToCSharpSerializer(ExpectationSerializer expectationSerializer) {
        this.expectationSerializer = expectationSerializer;
    }

    public String serialize(List<Expectation> expectations) {
        StringBuilder output = new StringBuilder();
        output.append("using System.Text.Json;").append(NEW_LINE);
        output.append("using System.Text.Json.Serialization;").append(NEW_LINE);
        output.append("using MockServer.Client;").append(NEW_LINE);
        output.append("using MockServer.Client.Models;").append(NEW_LINE);
        output.append(NEW_LINE);
        output.append("var jsonOptions = new JsonSerializerOptions").append(NEW_LINE);
        output.append("{").append(NEW_LINE);
        output.append("    PropertyNamingPolicy = JsonNamingPolicy.CamelCase,").append(NEW_LINE);
        output.append("    DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull").append(NEW_LINE);
        output.append("};").append(NEW_LINE);
        output.append(NEW_LINE);
        output.append("var client = new MockServerClient(\"localhost\", 1080);").append(NEW_LINE);
        if (expectations != null) {
            for (Expectation expectation : expectations) {
                if (expectation == null) {
                    continue;
                }
                String json = expectationSerializer.serialize(expectation);
                output.append(NEW_LINE);
                output.append("client.Upsert(JsonSerializer.Deserialize<Expectation>(@\"")
                    .append(csharpVerbatim(json))
                    .append("\", jsonOptions));").append(NEW_LINE);
            }
        }
        return output.toString();
    }

    /**
     * Escape {@code json} for embedding inside a C# verbatim string literal
     * ({@code @"..."}): only the double-quote needs escaping, by doubling it.
     */
    private static String csharpVerbatim(String json) {
        return json.replace("\"", "\"\"");
    }
}
