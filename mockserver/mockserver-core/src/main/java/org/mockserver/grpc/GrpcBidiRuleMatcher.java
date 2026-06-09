package org.mockserver.grpc;

import org.mockserver.model.GrpcBidiRule;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Transport-neutral helper that matches an inbound gRPC bidi message (as JSON)
 * against a {@link GrpcBidiRule}'s {@code matchJson} pattern.
 * <p>
 * Shared by the HTTP/2 bidi handler ({@code GrpcBidiStreamHandler}) and the HTTP/3
 * bidi handler ({@code Http3GrpcBidiStreamHandler}) so the matching semantics are
 * identical across transports:
 * <ul>
 *   <li>a {@code null}/empty pattern matches everything;</li>
 *   <li>exact string equality is tried first;</li>
 *   <li>then a regex match using {@link Pattern#DOTALL} (so {@code '.'} matches the
 *       newlines that protobuf's {@code JsonFormat.printer()} emits in multiline JSON);</li>
 *   <li>an invalid regex never matches (rather than throwing);</li>
 *   <li>{@link org.mockserver.model.NottableString#isNot()} inverts the result.</li>
 * </ul>
 */
public final class GrpcBidiRuleMatcher {

    private GrpcBidiRuleMatcher() {
        // utility class
    }

    /**
     * @param rule        the bidi rule whose {@code matchJson} pattern is evaluated
     * @param inboundJson the inbound message converted to JSON
     * @return {@code true} if the rule matches the inbound message
     */
    public static boolean matches(GrpcBidiRule rule, String inboundJson) {
        if (rule.getMatchJson() == null) {
            return true; // null matcher matches everything
        }
        String pattern = rule.getMatchJson().getValue();
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        boolean matched;
        if (inboundJson.equals(pattern)) {
            matched = true;
        } else {
            try {
                matched = Pattern.compile(pattern, Pattern.DOTALL).matcher(inboundJson).matches();
            } catch (PatternSyntaxException e) {
                matched = false;
            }
        }
        if (rule.getMatchJson().isNot()) {
            matched = !matched;
        }
        return matched;
    }
}
