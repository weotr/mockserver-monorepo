package org.mockserver.serialization.code;

import org.mockserver.mock.Expectation;
import org.mockserver.serialization.ExpectationSerializer;

import java.util.List;

/**
 * Generates copy-paste-ready Ruby expectation code for the MockServer Ruby
 * client ({@code mockserver-client}).
 * <p>
 * Following the same JSON-wrap pattern as the JavaScript/Python generators, the
 * generated code embeds each expectation's existing JSON serialization (the
 * same bytes produced by {@code format=JSON}) in a heredoc, parses it with
 * {@code JSON.parse}, reconstructs it via {@code MockServer::Expectation.from_hash}
 * and passes it to {@code client.upsert(...)}.
 * <p>
 * Verified against the Ruby client source:
 * <ul>
 *   <li>{@code MockServer::Client.new(host, port, ...)} (require {@code 'mockserver-client'})</li>
 *   <li>{@code def upsert(*expectations)}</li>
 *   <li>{@code MockServer::Expectation.from_hash(data)} (accepts a parsed JSON hash)</li>
 * </ul>
 * <p>
 * The JSON is embedded in a heredoc opened with {@code <<JSON}. The closing
 * delimiter is the literal token {@code JSON} alone on a line; Jackson's
 * serialized JSON can never produce such a line (every line is part of
 * structural JSON whose string values are escaped and quoted), so the heredoc
 * can never be terminated early by expectation content.
 * Example output for a single expectation:
 * <pre>
 * require 'mockserver-client'
 * require 'json'
 *
 * client = MockServer::Client.new('localhost', 1080)
 *
 * client.upsert(MockServer::Expectation.from_hash(JSON.parse(&lt;&lt;JSON)))
 * { ... }
 * JSON
 * </pre>
 *
 * @author jamesdbloom
 */
public class ExpectationToRubySerializer {

    private static final String NEW_LINE = "\n";
    private static final String HEREDOC_DELIMITER = "JSON";

    private final ExpectationSerializer expectationSerializer;

    public ExpectationToRubySerializer(ExpectationSerializer expectationSerializer) {
        this.expectationSerializer = expectationSerializer;
    }

    public String serialize(List<Expectation> expectations) {
        StringBuilder output = new StringBuilder();
        output.append("require 'mockserver-client'").append(NEW_LINE);
        output.append("require 'json'").append(NEW_LINE);
        output.append(NEW_LINE);
        output.append("client = MockServer::Client.new('localhost', 1080)").append(NEW_LINE);
        if (expectations != null) {
            for (Expectation expectation : expectations) {
                if (expectation == null) {
                    continue;
                }
                output.append(NEW_LINE);
                output.append("client.upsert(MockServer::Expectation.from_hash(JSON.parse(<<").append(HEREDOC_DELIMITER).append(")))").append(NEW_LINE);
                output.append(expectationSerializer.serialize(expectation)).append(NEW_LINE);
                output.append(HEREDOC_DELIMITER).append(NEW_LINE);
            }
        }
        return output.toString();
    }
}
