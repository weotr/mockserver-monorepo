package org.mockserver.serialization.code;

import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ExpectationSerializer;

import java.util.List;

/**
 * Generates copy-paste-ready Rust expectation code for the MockServer Rust
 * client ({@code mockserver-client} crate, module {@code mockserver_client}).
 * <p>
 * Following the same JSON-wrap pattern as the JavaScript/Python generators, the
 * generated code embeds each expectation's existing JSON serialization (the
 * same bytes produced by {@code format=JSON}) in a Rust raw string literal and
 * deserializes it with {@code serde_json::from_str::<Expectation>(...)}, then
 * passes it to {@code client.upsert(&[...])}.
 * <p>
 * Verified against the Rust client source:
 * <ul>
 *   <li>{@code ClientBuilder::new(host, port).build()?} → {@code Result<MockServerClient>}</li>
 *   <li>{@code pub fn upsert(&self, expectations: &[Expectation]) -> Result<Vec<Expectation>>}</li>
 *   <li>{@code Expectation} derives {@code Deserialize} (so {@code serde_json::from_str} works)</li>
 * </ul>
 * <p>
 * The JSON is embedded in a Rust raw string literal {@code r#"..."#}. A raw
 * literal opened with N {@code #} hashes is terminated by a double-quote
 * followed by N hashes, so the generator chooses the smallest N such that the
 * JSON never contains {@code "} followed by N {@code #} characters, guaranteeing
 * the literal cannot be terminated early by expectation content.
 * Example output:
 * <pre>
 * use mockserver_client::{ClientBuilder, Expectation};
 *
 * fn main() -&gt; Result&lt;(), Box&lt;dyn std::error::Error&gt;&gt; {
 *     let client = ClientBuilder::new("localhost", 1080).build()?;
 *
 *     client.upsert(&amp;[serde_json::from_str::&lt;Expectation&gt;(r#"{ ... }"#)?])?;
 *
 *     Ok(())
 * }
 * </pre>
 *
 * @author jamesdbloom
 */
public class ExpectationToRustSerializer {

    private static final String NEW_LINE = "\n";
    private static final String INDENT = "    ";

    private final ExpectationSerializer expectationSerializer;

    public ExpectationToRustSerializer(ExpectationSerializer expectationSerializer) {
        this.expectationSerializer = expectationSerializer;
    }

    public String serialize(List<Expectation> expectations) {
        StringBuilder output = new StringBuilder();
        output.append("use mockserver_client::{ClientBuilder, Expectation};").append(NEW_LINE);
        output.append(NEW_LINE);
        output.append("fn main() -> Result<(), Box<dyn std::error::Error>> {").append(NEW_LINE);
        output.append(INDENT).append("let client = ClientBuilder::new(\"localhost\", 1080).build()?;").append(NEW_LINE);
        if (expectations != null) {
            for (Expectation expectation : expectations) {
                if (expectation == null) {
                    continue;
                }
                String json = expectationSerializer.serialize(expectation);
                String hashes = rawStringHashes(json);
                output.append(NEW_LINE);
                output.append(INDENT)
                    .append("client.upsert(&[serde_json::from_str::<Expectation>(r")
                    .append(hashes).append("\"")
                    .append(json)
                    .append("\"").append(hashes)
                    .append(")?])?;").append(NEW_LINE);
            }
        }
        output.append(NEW_LINE);
        output.append(INDENT).append("Ok(())").append(NEW_LINE);
        output.append("}").append(NEW_LINE);
        return output.toString();
    }

    /**
     * Choose the smallest run of {@code #} hashes such that a raw string literal
     * {@code r<hashes>"..."<hashes>} cannot be terminated early by the JSON: the
     * literal ends at a {@code "} followed by that many {@code #}. Start at one
     * hash and increase until the JSON contains no {@code "} followed by that
     * many consecutive {@code #} characters.
     */
    private static String rawStringHashes(String json) {
        int hashes = 1;
        while (containsQuoteFollowedByHashes(json, hashes)) {
            hashes++;
        }
        StringBuilder sb = new StringBuilder(hashes);
        for (int i = 0; i < hashes; i++) {
            sb.append('#');
        }
        return sb.toString();
    }

    private static boolean containsQuoteFollowedByHashes(String json, int hashes) {
        for (int i = 0; i < json.length(); i++) {
            if (json.charAt(i) == '"') {
                int h = 0;
                int j = i + 1;
                while (j < json.length() && json.charAt(j) == '#') {
                    h++;
                    j++;
                }
                if (h >= hashes) {
                    return true;
                }
            }
        }
        return false;
    }
}
