package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.SelectionSetMatchType;

import java.util.Set;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class GraphQLAstMatcherTest {

    // --- extractOperationType ---

    @Test
    public void shouldExtractQueryOperationType() {
        assertThat(GraphQLAstMatcher.extractOperationType("query { hero { name } }"), is("query"));
    }

    @Test
    public void shouldExtractMutationOperationType() {
        assertThat(GraphQLAstMatcher.extractOperationType("mutation CreateUser { createUser { id } }"), is("mutation"));
    }

    @Test
    public void shouldExtractSubscriptionOperationType() {
        assertThat(GraphQLAstMatcher.extractOperationType("subscription OnUpdate { userUpdated { id } }"), is("subscription"));
    }

    @Test
    public void shouldDefaultToQueryForShorthandSyntax() {
        assertThat(GraphQLAstMatcher.extractOperationType("{ hero { name } }"), is("query"));
    }

    @Test
    public void shouldDefaultToQueryForNull() {
        assertThat(GraphQLAstMatcher.extractOperationType(null), is("query"));
    }

    @Test
    public void shouldIgnoreCommentsBeforeOperationType() {
        assertThat(GraphQLAstMatcher.extractOperationType("# a comment\nmutation Foo { bar }"), is("mutation"));
    }

    @Test
    public void shouldHandleCaseInsensitiveOperationType() {
        assertThat(GraphQLAstMatcher.extractOperationType("QUERY { hero { name } }"), is("query"));
        assertThat(GraphQLAstMatcher.extractOperationType("Mutation CreateUser { createUser { id } }"), is("mutation"));
    }

    // --- extractOperationName ---

    @Test
    public void shouldExtractOperationName() {
        assertThat(GraphQLAstMatcher.extractOperationName("query GetHero { hero { name } }"), is("GetHero"));
    }

    @Test
    public void shouldReturnEmptyStringWhenNoOperationName() {
        assertThat(GraphQLAstMatcher.extractOperationName("query { hero { name } }"), is(""));
    }

    @Test
    public void shouldReturnEmptyStringForShorthandQuery() {
        assertThat(GraphQLAstMatcher.extractOperationName("{ hero { name } }"), is(""));
    }

    @Test
    public void shouldReturnEmptyStringForNull() {
        assertThat(GraphQLAstMatcher.extractOperationName(null), is(""));
    }

    @Test
    public void shouldExtractMutationOperationName() {
        assertThat(GraphQLAstMatcher.extractOperationName("mutation CreateUser($input: Input!) { createUser(input: $input) { id } }"), is("CreateUser"));
    }

    // --- extractTopLevelFields ---

    @Test
    public void shouldExtractSingleField() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { hero { name } }");
        assertThat(fields.size(), is(1));
        assertThat("single-field query should contain 'hero'", fields.contains("hero"), is(true));
    }

    @Test
    public void shouldExtractMultipleFields() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { hero { name } starships { id } }");
        assertThat(fields.size(), is(2));
        assertThat("multi-field query should contain 'hero'", fields.contains("hero"), is(true));
        assertThat("multi-field query should contain 'starships'", fields.contains("starships"), is(true));
    }

    @Test
    public void shouldExtractFieldsFromShorthandQuery() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("{ user { name } posts { title } }");
        assertThat(fields.size(), is(2));
        assertThat("shorthand query should contain 'user'", fields.contains("user"), is(true));
        assertThat("shorthand query should contain 'posts'", fields.contains("posts"), is(true));
    }

    @Test
    public void shouldNotIncludeNestedFieldNames() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { hero { name age address { city } } }");
        assertThat(fields.size(), is(1));
        assertThat("top-level fields should contain 'hero'", fields.contains("hero"), is(true));
        assertThat("nested field 'name' should not be top-level", fields.contains("name"), is(false));
        assertThat("nested field 'age' should not be top-level", fields.contains("age"), is(false));
        assertThat("nested field 'address' should not be top-level", fields.contains("address"), is(false));
        assertThat("nested field 'city' should not be top-level", fields.contains("city"), is(false));
    }

    @Test
    public void shouldHandleFieldsWithArguments() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { user(id: 1) { name } }");
        assertThat("field with arguments should be extracted as 'user'", fields.contains("user"), is(true));
    }

    @Test
    public void shouldReturnEmptySetForNull() {
        assertThat("null input should produce empty field set", GraphQLAstMatcher.extractTopLevelFields(null).isEmpty(), is(true));
    }

    @Test
    public void shouldReturnEmptySetForNoSelectionSet() {
        assertThat("query without selection set should produce empty field set", GraphQLAstMatcher.extractTopLevelFields("query GetHero").isEmpty(), is(true));
    }

    @Test
    public void shouldSkipOnKeyword() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { hero { name } ... on Droid { primaryFunction } }");
        assertThat("inline fragment should preserve 'hero' field", fields.contains("hero"), is(true));
        assertThat("'on' keyword should not appear as a field", fields.contains("on"), is(false));
    }

    @Test
    public void shouldHandleFieldsWithVariableDefinitions() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query GetUser($id: ID!) { user(id: $id) { name } }");
        assertThat("query with variable definitions should contain 'user'", fields.contains("user"), is(true));
        assertThat(fields.size(), is(1));
    }

    // --- extractQueryFromBody ---

    @Test
    public void shouldExtractQueryFromJsonWrapper() {
        String body = "{\"query\": \"{ users { id name } }\", \"variables\": {}}";
        assertThat(GraphQLAstMatcher.extractQueryFromBody(body), is("{ users { id name } }"));
    }

    @Test
    public void shouldReturnRawGraphQLQueryAsIs() {
        String body = "query { users { id } }";
        assertThat(GraphQLAstMatcher.extractQueryFromBody(body), is("query { users { id } }"));
    }

    @Test
    public void shouldHandleEscapedNewlinesInJsonQuery() {
        String body = "{\"query\": \"{\\n  users {\\n    id\\n  }\\n}\"}";
        assertThat(GraphQLAstMatcher.extractQueryFromBody(body), is("{\n  users {\n    id\n  }\n}"));
    }

    // --- AST_SUBSET matching ---

    @Test
    public void astSubsetShouldMatchWhenExpectedFieldsPresent() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Actual has more fields — subset matches
        assertThat("AST_SUBSET should match when actual has superset of expected fields", matcher.matches("query { hero { name } starships { id } }"), is(true));
    }

    @Test
    public void astSubsetShouldNotMatchWhenExpectedFieldMissing() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Actual is missing the expected field
        assertThat("AST_SUBSET should not match when expected field 'hero' is absent", matcher.matches("query { starships { id } }"), is(false));
    }

    @Test
    public void astSubsetShouldMatchMultipleExpectedFields() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero", "starships");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_SUBSET should match when all expected fields present plus extras", matcher.matches("query { hero { name } starships { id } extra { data } }"), is(true));
    }

    @Test
    public void astSubsetShouldUseFieldsFromQueryWhenNoExplicitFields() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_SUBSET should infer fields from query when none explicitly set", matcher.matches("query { hero { name age } starships { id } }"), is(true));
    }

    @Test
    public void astSubsetShouldCheckOperationType() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Actual is a mutation, not a query
        assertThat("AST_SUBSET should reject mutation when query expected", matcher.matches("mutation { hero { name } }"), is(false));
    }

    @Test
    public void astSubsetShouldCheckOperationName() {
        GraphQLBody body = GraphQLBody.graphQL("query GetHero { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_SUBSET should match when operation name matches", matcher.matches("query GetHero { hero { name } starships { id } }"), is(true));
        assertThat("AST_SUBSET should reject mismatched operation name 'GetVillain'", matcher.matches("query GetVillain { hero { name } }"), is(false));
    }

    @Test
    public void astSubsetShouldNotMatchNullBody() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_SUBSET should not match null body", matcher.matches(null), is(false));
    }

    @Test
    public void astSubsetShouldNotMatchEmptyBody() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_SUBSET should not match empty string", matcher.matches(""), is(false));
        assertThat("AST_SUBSET should not match blank string", matcher.matches("   "), is(false));
    }

    // --- AST_EXACT matching ---

    @Test
    public void astExactShouldMatchIdenticalFieldSet() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_EXACT should match identical top-level field set", matcher.matches("query { hero { name }   starships { id } }"), is(true));
    }

    @Test
    public void astExactShouldNotMatchMissingField() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Missing starships
        assertThat("AST_EXACT should reject when 'starships' is missing", matcher.matches("query { hero { name } }"), is(false));
    }

    @Test
    public void astExactShouldNotMatchExtraField() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Extra field
        assertThat("AST_EXACT should reject when extra field 'extra' is present", matcher.matches("query { hero starships extra }"), is(false));
    }

    @Test
    public void astExactShouldMatchRegardlessOfWhitespace() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_EXACT should ignore whitespace differences", matcher.matches("query {\n  hero {\n    name\n  }\n  starships {\n    id\n  }\n}"), is(true));
    }

    @Test
    public void astExactShouldCheckOperationType() {
        GraphQLBody body = GraphQLBody.graphQL("mutation { createUser }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_EXACT should match when operation type is mutation", matcher.matches("mutation { createUser { id } }"), is(true));
        assertThat("AST_EXACT should reject query when mutation expected", matcher.matches("query { createUser { id } }"), is(false));
    }

    @Test
    public void astExactShouldCheckOperationName() {
        GraphQLBody body = GraphQLBody.graphQL("query GetHero { hero }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_EXACT should match when operation name is GetHero", matcher.matches("query GetHero { hero { name } }"), is(true));
        assertThat("AST_EXACT should reject mismatched operation name 'GetVillain'", matcher.matches("query GetVillain { hero { name } }"), is(false));
    }

    @Test
    public void astExactShouldUseExplicitFieldsWhenProvided() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT)
            .withFields("hero", "starships");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_EXACT with explicit fields should match when both present", matcher.matches("query { hero { name } starships { id } }"), is(true));
        assertThat("AST_EXACT with explicit fields should reject when 'starships' missing", matcher.matches("query { hero { name } }"), is(false));
    }

    // --- JSON-wrapped body matching ---

    @Test
    public void astSubsetShouldHandleJsonWrappedGraphQL() {
        GraphQLBody body = GraphQLBody.graphQL("query { users }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("users");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_SUBSET should match JSON-wrapped GraphQL body", matcher.matches("{\"query\":\"{ users { id name } }\",\"variables\":{}}"), is(true));
    }

    @Test
    public void astExactShouldHandleJsonWrappedGraphQL() {
        GraphQLBody body = GraphQLBody.graphQL("query { users }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT)
            .withFields("users");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_EXACT should match JSON-wrapped GraphQL body", matcher.matches("{\"query\":\"{ users { id name } }\",\"variables\":{}}"), is(true));
    }

    // --- NORMALISED_STRING mode (should not match — delegates to GraphQLMatcher) ---

    @Test
    public void normalisedStringModeShouldNotMatchInAstMatcher() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }");
        // selectionSetMatchType defaults to null which maps to NORMALISED_STRING
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("NORMALISED_STRING mode should not match in AST matcher", matcher.matches("query { hero { name } }"), is(false));
    }

    // --- Edge cases ---

    @Test
    public void shouldHandleQueryWithComments() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_SUBSET should match query with leading comment", matcher.matches("# fetch hero\nquery { hero { name } }"), is(true));
    }

    @Test
    public void shouldHandleQueryWithDirectives() {
        GraphQLBody body = GraphQLBody.graphQL("query GetHero @cached { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_SUBSET should match query with @cached directive", matcher.matches("query GetHero @cached { hero { name } extra { data } }"), is(true));
    }

    @Test
    public void shouldHandleSubscription() {
        GraphQLBody body = GraphQLBody.graphQL("subscription OnUpdate { userUpdated }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertThat("AST_EXACT should match subscription with correct operation type", matcher.matches("subscription OnUpdate { userUpdated { id name } }"), is(true));
        assertThat("AST_EXACT should reject query when subscription expected", matcher.matches("query OnUpdate { userUpdated { id } }"), is(false));
    }

    @Test
    public void shouldHandleFieldsWithAliases() {
        // Aliases appear before the colon: "smallPic: profilePic(size: 64)"
        // The parser picks up both the alias and the field name since it splits on non-alphanumeric
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { smallPic: profilePic(size: 64) { url } }");
        // "smallPic" and "profilePic" are both extracted since the parser reads identifiers
        assertThat("aliased field should extract 'smallPic'", fields.contains("smallPic"), is(true));
    }

    @Test
    public void shouldHandleFieldsWithStringArguments() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { search(query: \"hello world\") { results } }");
        assertThat("field with string argument should extract 'search'", fields.contains("search"), is(true));
        assertThat("string argument content 'hello' should not be a field", fields.contains("hello"), is(false));
        assertThat("string argument content 'world' should not be a field", fields.contains("world"), is(false));
    }
}
