package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;

import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class GraphQLMatcherTest {

    @Test
    public void shouldMatchSimpleGraphQLQuery() {
        assertThat("simple query should match identical query", new GraphQLMatcher(new MockServerLogger(), "{ user(id: 1) { name } }", null, null).matches(null, "{\"query\": \"{ user(id: 1) { name } }\"}"), is(true));
    }

    @Test
    public void shouldReturnFalseOnReDoSOperationNamePatternRatherThanHanging() {
        long previousTimeout = ConfigurationProperties.regexMatchingTimeoutMillis();
        try {
            ConfigurationProperties.regexMatchingTimeoutMillis(200L);
            // catastrophic-backtracking regex + non-matching input that would otherwise hang for a long time
            String evilOperationName = "(a+)+$";
            String evilInput = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa!";
            String query = "query GetUser { user(id: 1) { name } }";
            String requestBody = "{\"query\": \"query GetUser { user(id: 1) { name } }\", \"operationName\": \"" + evilInput + "\"}";

            long startMillis = System.currentTimeMillis();
            boolean matched = new GraphQLMatcher(new MockServerLogger(), query, evilOperationName, null).matches(null, requestBody);
            long elapsedMillis = System.currentTimeMillis() - startMillis;

            assertThat("ReDoS operationName pattern must not match the request", matched, is(false));
            assertThat("regex evaluation should be bounded by the timeout but took " + elapsedMillis + "ms", elapsedMillis < 2_000L, is(true));
        } finally {
            ConfigurationProperties.regexMatchingTimeoutMillis(previousTimeout);
        }
    }

    @Test
    public void shouldMatchQueryWithWhitespaceNormalization() {
        assertThat("single-line expected should match multi-line actual", new GraphQLMatcher(new MockServerLogger(), "{ user(id: 1) { name email } }", null, null).matches(null, "{\"query\": \"{\\n  user(id: 1) {\\n    name\\n    email\\n  }\\n}\"}"), is(true));
        assertThat("multi-line expected should match single-line actual", new GraphQLMatcher(new MockServerLogger(), "{\n  user(id: 1) {\n    name\n    email\n  }\n}", null, null).matches(null, "{\"query\": \"{ user(id: 1) { name email } }\"}"), is(true));
    }

    @Test
    public void shouldMatchQueryWithOperationName() {
        assertThat("exact operation name 'GetUser' should match", new GraphQLMatcher(new MockServerLogger(), "query GetUser { user(id: 1) { name } }", "GetUser", null).matches(null, "{\"query\": \"query GetUser { user(id: 1) { name } }\", \"operationName\": \"GetUser\"}"), is(true));
    }

    @Test
    public void shouldMatchQueryWithRegexOperationName() {
        assertThat("regex 'Get.*' should match 'GetUser'", new GraphQLMatcher(new MockServerLogger(), "query GetUser { user(id: 1) { name } }", "Get.*", null).matches(null, "{\"query\": \"query GetUser { user(id: 1) { name } }\", \"operationName\": \"GetUser\"}"), is(true));
        assertThat("regex 'Get(User|Account)' should match 'GetUser'", new GraphQLMatcher(new MockServerLogger(), "query GetUser { user(id: 1) { name } }", "Get(User|Account)", null).matches(null, "{\"query\": \"query GetUser { user(id: 1) { name } }\", \"operationName\": \"GetUser\"}"), is(true));
        assertThat("regex 'Delete.*' should not match 'GetUser'", new GraphQLMatcher(new MockServerLogger(), "query GetUser { user(id: 1) { name } }", "Delete.*", null).matches(null, "{\"query\": \"query GetUser { user(id: 1) { name } }\", \"operationName\": \"GetUser\"}"), is(false));
    }

    @Test
    public void shouldNotMatchWhenQueryDiffers() {
        assertThat("different query (id:2 vs id:1) should not match", new GraphQLMatcher(new MockServerLogger(), "{ user(id: 1) { name } }", null, null).matches(null, "{\"query\": \"{ user(id: 2) { email } }\"}"), is(false));
    }

    @Test
    public void shouldNotMatchWhenOperationNameDiffers() {
        assertThat("operation name 'ListUsers' should not match expected 'GetUser'", new GraphQLMatcher(new MockServerLogger(), "query GetUser { user(id: 1) { name } }", "GetUser", null).matches(null, "{\"query\": \"query GetUser { user(id: 1) { name } }\", \"operationName\": \"ListUsers\"}"), is(false));
    }

    @Test
    public void shouldNotMatchInvalidJson() {
        assertThat("invalid JSON body should not match", new GraphQLMatcher(new MockServerLogger(), "{ user(id: 1) { name } }", null, null).matches(null, "{not valid json"), is(false));
    }

    @Test
    public void shouldNotMatchNullBody() {
        assertThat("null body should not match", new GraphQLMatcher(new MockServerLogger(), "{ user(id: 1) { name } }", null, null).matches(null, null), is(false));
    }

    @Test
    public void shouldNotMatchEmptyBody() {
        assertThat("empty body should not match", new GraphQLMatcher(new MockServerLogger(), "{ user(id: 1) { name } }", null, null).matches(null, ""), is(false));
    }

    @Test
    public void shouldNotMatchMissingQueryField() {
        assertThat("body without 'query' field should not match", new GraphQLMatcher(new MockServerLogger(), "{ user(id: 1) { name } }", null, null).matches(null, "{\"operationName\": \"GetUser\"}"), is(false));
    }

    @Test
    public void shouldSupportNotMatcher() {
        assertThat("NOT matcher should match when underlying query differs", notMatcher(new GraphQLMatcher(new MockServerLogger(), "{ user(id: 1) { name } }", null, null)).matches(null, "{\"query\": \"{ user(id: 2) { email } }\"}"), is(true));
        assertThat("NOT matcher should not match when underlying query is identical", notMatcher(new GraphQLMatcher(new MockServerLogger(), "{ user(id: 1) { name } }", null, null)).matches(null, "{\"query\": \"{ user(id: 1) { name } }\"}"), is(false));
    }

    @Test
    public void shouldMatchWithoutOperationNameWhenNotRequired() {
        assertThat("no operationName filter should match body with operationName", new GraphQLMatcher(new MockServerLogger(), "{ user(id: 1) { name } }", null, null).matches(null, "{\"query\": \"{ user(id: 1) { name } }\", \"operationName\": \"GetUser\"}"), is(true));
        assertThat("no operationName filter should match body without operationName", new GraphQLMatcher(new MockServerLogger(), "{ user(id: 1) { name } }", null, null).matches(null, "{\"query\": \"{ user(id: 1) { name } }\"}"), is(true));
    }

    @Test
    public void shouldMatchQueryWithVariables() {
        assertThat("query with variables should match", new GraphQLMatcher(new MockServerLogger(), "query GetUser($id: ID!) { user(id: $id) { name } }", null, null).matches(null, "{\"query\": \"query GetUser($id: ID!) { user(id: $id) { name } }\", \"variables\": {\"id\": 1}}"), is(true));
    }

    @Test
    public void shouldMatchMutationQuery() {
        assertThat("mutation query should match", new GraphQLMatcher(new MockServerLogger(), "mutation CreateUser($name: String!) { createUser(name: $name) { id name } }", null, null).matches(null, "{\"query\": \"mutation CreateUser($name: String!) { createUser(name: $name) { id name } }\"}"), is(true));
    }

    @Test
    public void shouldMatchSubscriptionQuery() {
        assertThat("subscription query should match", new GraphQLMatcher(new MockServerLogger(), "subscription OnUserCreated { userCreated { id name } }", null, null).matches(null, "{\"query\": \"subscription OnUserCreated { userCreated { id name } }\"}"), is(true));
    }

    @Test
    public void shouldMatchQueryWithFragments() {
        String queryWithFragment = "query GetUser { user(id: 1) { ...UserFields } } fragment UserFields on User { name email }";
        assertThat("query with fragments should match", new GraphQLMatcher(new MockServerLogger(), queryWithFragment, null, null).matches(null, "{\"query\": \"query GetUser { user(id: 1) { ...UserFields } } fragment UserFields on User { name email }\"}"), is(true));
    }

    @Test
    public void shouldMatchCompactPunctuationFormatting() {
        assertThat("spaced query should match compact query", new GraphQLMatcher(new MockServerLogger(), "{ user { id } }", null, null).matches(null, "{\"query\": \"{user{id}}\"}"), is(true));
        assertThat("compact query should match spaced query", new GraphQLMatcher(new MockServerLogger(), "{user{id}}", null, null).matches(null, "{\"query\": \"{ user { id } }\"}"), is(true));
    }

    @Test
    public void shouldPreserveStringLiteralWhitespace() {
        assertThat("different whitespace inside string literal should not match", new GraphQLMatcher(new MockServerLogger(), "{ search(text: \"a b\") { id } }", null, null).matches(null, "{\"query\": \"{ search(text: \\\"a   b\\\") { id } }\"}"), is(false));
    }

    @Test
    public void shouldHandleCommentsInQuery() {
        assertThat("query with leading comment should match", new GraphQLMatcher(new MockServerLogger(), "{ user { name } }", null, null).matches(null, "{\"query\": \"# fetch user\\n{ user { name } }\"}"), is(true));
    }

    @Test
    public void shouldHandleCommasAsInsignificant() {
        assertThat("commas between fields should be insignificant", new GraphQLMatcher(new MockServerLogger(), "{ user { name email } }", null, null).matches(null, "{\"query\": \"{ user { name, email } }\"}"), is(true));
    }

    @Test
    public void shouldMatchBlockStringLiterals() {
        String expected = "{ schema { description } }";
        assertThat("block string query should match compact form", new GraphQLMatcher(new MockServerLogger(), expected, null, null).matches(null, "{\"query\": \"{schema{description}}\"}"), is(true));
    }

    @Test
    public void shouldHandleEscapedCharactersInStrings() {
        String query = "{ search(text: \"hello\\\"world\") { id } }";
        assertThat("escaped quotes in string arguments should match", new GraphQLMatcher(new MockServerLogger(), query, null, null).matches(null, "{\"query\": \"{ search(text: \\\"hello\\\\\\\"world\\\") { id } }\"}"), is(true));
    }

    @Test
    public void shouldValidateVariablesWithSchema() {
        String schema = "{\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"integer\"}}, \"required\": [\"id\"]}";
        assertThat("valid variables should pass schema validation", new GraphQLMatcher(new MockServerLogger(), "query GetUser($id: ID!) { user(id: $id) { name } }", null, schema).matches(null, "{\"query\": \"query GetUser($id: ID!) { user(id: $id) { name } }\", \"variables\": {\"id\": 1}}"), is(true));
    }

    @Test
    public void shouldRejectInvalidVariablesWithSchema() {
        String schema = "{\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"integer\"}}, \"required\": [\"id\"]}";
        assertThat("variables missing required 'id' should fail schema validation", new GraphQLMatcher(new MockServerLogger(), "query GetUser($id: ID!) { user(id: $id) { name } }", null, schema).matches(null, "{\"query\": \"query GetUser($id: ID!) { user(id: $id) { name } }\", \"variables\": {\"name\": \"test\"}}"), is(false));
    }

    @Test
    public void shouldRejectMissingVariablesWhenSchemaRequired() {
        String schema = "{\"type\": \"object\", \"properties\": {\"id\": {\"type\": \"integer\"}}, \"required\": [\"id\"]}";
        assertThat("missing variables object should fail when schema requires fields", new GraphQLMatcher(new MockServerLogger(), "query GetUser($id: ID!) { user(id: $id) { name } }", null, schema).matches(null, "{\"query\": \"query GetUser($id: ID!) { user(id: $id) { name } }\"}"), is(false));
    }

    @Test
    public void shouldAcceptNullVariablesWhenNoSchema() {
        assertThat("null variables should be accepted when no schema set", new GraphQLMatcher(new MockServerLogger(), "{ user { name } }", null, null).matches(null, "{\"query\": \"{ user { name } }\", \"variables\": null}"), is(true));
    }

    @Test
    public void shouldNormalizeQueryCorrectly() {
        assertThat(GraphQLMatcher.normalizeQuery("{ user { id } }"), is("{user{id}}"));
        assertThat(GraphQLMatcher.normalizeQuery("{user{id}}"), is("{user{id}}"));
        assertThat(GraphQLMatcher.normalizeQuery("  {  user  {  id  }  }  "), is("{user{id}}"));
        assertThat(GraphQLMatcher.normalizeQuery("query GetUser { user(id: 1) { name email } }"), is("query GetUser{user(id:1){name email}}"));
        assertThat(GraphQLMatcher.normalizeQuery("{ search(text: \"a b\") { id } }"), is("{search(text:\"a b\"){id}}"));
    }
}
