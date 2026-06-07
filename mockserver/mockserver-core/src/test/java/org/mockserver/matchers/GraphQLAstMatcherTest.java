package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.model.GraphQLBody;
import org.mockserver.model.SelectionSetMatchType;

import java.util.Set;

import static junit.framework.TestCase.*;

public class GraphQLAstMatcherTest {

    // --- extractOperationType ---

    @Test
    public void shouldExtractQueryOperationType() {
        assertEquals("query", GraphQLAstMatcher.extractOperationType("query { hero { name } }"));
    }

    @Test
    public void shouldExtractMutationOperationType() {
        assertEquals("mutation", GraphQLAstMatcher.extractOperationType("mutation CreateUser { createUser { id } }"));
    }

    @Test
    public void shouldExtractSubscriptionOperationType() {
        assertEquals("subscription", GraphQLAstMatcher.extractOperationType("subscription OnUpdate { userUpdated { id } }"));
    }

    @Test
    public void shouldDefaultToQueryForShorthandSyntax() {
        assertEquals("query", GraphQLAstMatcher.extractOperationType("{ hero { name } }"));
    }

    @Test
    public void shouldDefaultToQueryForNull() {
        assertEquals("query", GraphQLAstMatcher.extractOperationType(null));
    }

    @Test
    public void shouldIgnoreCommentsBeforeOperationType() {
        assertEquals("mutation", GraphQLAstMatcher.extractOperationType("# a comment\nmutation Foo { bar }"));
    }

    @Test
    public void shouldHandleCaseInsensitiveOperationType() {
        assertEquals("query", GraphQLAstMatcher.extractOperationType("QUERY { hero { name } }"));
        assertEquals("mutation", GraphQLAstMatcher.extractOperationType("Mutation CreateUser { createUser { id } }"));
    }

    // --- extractOperationName ---

    @Test
    public void shouldExtractOperationName() {
        assertEquals("GetHero", GraphQLAstMatcher.extractOperationName("query GetHero { hero { name } }"));
    }

    @Test
    public void shouldReturnEmptyStringWhenNoOperationName() {
        assertEquals("", GraphQLAstMatcher.extractOperationName("query { hero { name } }"));
    }

    @Test
    public void shouldReturnEmptyStringForShorthandQuery() {
        assertEquals("", GraphQLAstMatcher.extractOperationName("{ hero { name } }"));
    }

    @Test
    public void shouldReturnEmptyStringForNull() {
        assertEquals("", GraphQLAstMatcher.extractOperationName(null));
    }

    @Test
    public void shouldExtractMutationOperationName() {
        assertEquals("CreateUser", GraphQLAstMatcher.extractOperationName("mutation CreateUser($input: Input!) { createUser(input: $input) { id } }"));
    }

    // --- extractTopLevelFields ---

    @Test
    public void shouldExtractSingleField() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { hero { name } }");
        assertEquals(1, fields.size());
        assertTrue(fields.contains("hero"));
    }

    @Test
    public void shouldExtractMultipleFields() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { hero { name } starships { id } }");
        assertEquals(2, fields.size());
        assertTrue(fields.contains("hero"));
        assertTrue(fields.contains("starships"));
    }

    @Test
    public void shouldExtractFieldsFromShorthandQuery() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("{ user { name } posts { title } }");
        assertEquals(2, fields.size());
        assertTrue(fields.contains("user"));
        assertTrue(fields.contains("posts"));
    }

    @Test
    public void shouldNotIncludeNestedFieldNames() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { hero { name age address { city } } }");
        assertEquals(1, fields.size());
        assertTrue(fields.contains("hero"));
        assertFalse(fields.contains("name"));
        assertFalse(fields.contains("age"));
        assertFalse(fields.contains("address"));
        assertFalse(fields.contains("city"));
    }

    @Test
    public void shouldHandleFieldsWithArguments() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { user(id: 1) { name } }");
        assertTrue(fields.contains("user"));
    }

    @Test
    public void shouldReturnEmptySetForNull() {
        assertTrue(GraphQLAstMatcher.extractTopLevelFields(null).isEmpty());
    }

    @Test
    public void shouldReturnEmptySetForNoSelectionSet() {
        assertTrue(GraphQLAstMatcher.extractTopLevelFields("query GetHero").isEmpty());
    }

    @Test
    public void shouldSkipOnKeyword() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { hero { name } ... on Droid { primaryFunction } }");
        assertTrue(fields.contains("hero"));
        assertFalse(fields.contains("on"));
    }

    @Test
    public void shouldHandleFieldsWithVariableDefinitions() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query GetUser($id: ID!) { user(id: $id) { name } }");
        assertTrue(fields.contains("user"));
        assertEquals(1, fields.size());
    }

    // --- extractQueryFromBody ---

    @Test
    public void shouldExtractQueryFromJsonWrapper() {
        String body = "{\"query\": \"{ users { id name } }\", \"variables\": {}}";
        assertEquals("{ users { id name } }", GraphQLAstMatcher.extractQueryFromBody(body));
    }

    @Test
    public void shouldReturnRawGraphQLQueryAsIs() {
        String body = "query { users { id } }";
        assertEquals("query { users { id } }", GraphQLAstMatcher.extractQueryFromBody(body));
    }

    @Test
    public void shouldHandleEscapedNewlinesInJsonQuery() {
        String body = "{\"query\": \"{\\n  users {\\n    id\\n  }\\n}\"}";
        assertEquals("{\n  users {\n    id\n  }\n}", GraphQLAstMatcher.extractQueryFromBody(body));
    }

    // --- AST_SUBSET matching ---

    @Test
    public void astSubsetShouldMatchWhenExpectedFieldsPresent() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Actual has more fields — subset matches
        assertTrue(matcher.matches("query { hero { name } starships { id } }"));
    }

    @Test
    public void astSubsetShouldNotMatchWhenExpectedFieldMissing() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Actual is missing the expected field
        assertFalse(matcher.matches("query { starships { id } }"));
    }

    @Test
    public void astSubsetShouldMatchMultipleExpectedFields() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero", "starships");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertTrue(matcher.matches("query { hero { name } starships { id } extra { data } }"));
    }

    @Test
    public void astSubsetShouldUseFieldsFromQueryWhenNoExplicitFields() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertTrue(matcher.matches("query { hero { name age } starships { id } }"));
    }

    @Test
    public void astSubsetShouldCheckOperationType() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Actual is a mutation, not a query
        assertFalse(matcher.matches("mutation { hero { name } }"));
    }

    @Test
    public void astSubsetShouldCheckOperationName() {
        GraphQLBody body = GraphQLBody.graphQL("query GetHero { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertTrue(matcher.matches("query GetHero { hero { name } starships { id } }"));
        assertFalse(matcher.matches("query GetVillain { hero { name } }"));
    }

    @Test
    public void astSubsetShouldNotMatchNullBody() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertFalse(matcher.matches(null));
    }

    @Test
    public void astSubsetShouldNotMatchEmptyBody() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertFalse(matcher.matches(""));
        assertFalse(matcher.matches("   "));
    }

    // --- AST_EXACT matching ---

    @Test
    public void astExactShouldMatchIdenticalFieldSet() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertTrue(matcher.matches("query { hero { name }   starships { id } }"));
    }

    @Test
    public void astExactShouldNotMatchMissingField() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Missing starships
        assertFalse(matcher.matches("query { hero { name } }"));
    }

    @Test
    public void astExactShouldNotMatchExtraField() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        // Extra field
        assertFalse(matcher.matches("query { hero starships extra }"));
    }

    @Test
    public void astExactShouldMatchRegardlessOfWhitespace() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertTrue(matcher.matches("query {\n  hero {\n    name\n  }\n  starships {\n    id\n  }\n}"));
    }

    @Test
    public void astExactShouldCheckOperationType() {
        GraphQLBody body = GraphQLBody.graphQL("mutation { createUser }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertTrue(matcher.matches("mutation { createUser { id } }"));
        assertFalse(matcher.matches("query { createUser { id } }"));
    }

    @Test
    public void astExactShouldCheckOperationName() {
        GraphQLBody body = GraphQLBody.graphQL("query GetHero { hero }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertTrue(matcher.matches("query GetHero { hero { name } }"));
        assertFalse(matcher.matches("query GetVillain { hero { name } }"));
    }

    @Test
    public void astExactShouldUseExplicitFieldsWhenProvided() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero starships }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT)
            .withFields("hero", "starships");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertTrue(matcher.matches("query { hero { name } starships { id } }"));
        assertFalse(matcher.matches("query { hero { name } }"));
    }

    // --- JSON-wrapped body matching ---

    @Test
    public void astSubsetShouldHandleJsonWrappedGraphQL() {
        GraphQLBody body = GraphQLBody.graphQL("query { users }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("users");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertTrue(matcher.matches("{\"query\":\"{ users { id name } }\",\"variables\":{}}"));
    }

    @Test
    public void astExactShouldHandleJsonWrappedGraphQL() {
        GraphQLBody body = GraphQLBody.graphQL("query { users }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT)
            .withFields("users");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertTrue(matcher.matches("{\"query\":\"{ users { id name } }\",\"variables\":{}}"));
    }

    // --- NORMALISED_STRING mode (should not match — delegates to GraphQLMatcher) ---

    @Test
    public void normalisedStringModeShouldNotMatchInAstMatcher() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }");
        // selectionSetMatchType defaults to null which maps to NORMALISED_STRING
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertFalse(matcher.matches("query { hero { name } }"));
    }

    // --- Edge cases ---

    @Test
    public void shouldHandleQueryWithComments() {
        GraphQLBody body = GraphQLBody.graphQL("query { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET)
            .withFields("hero");
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertTrue(matcher.matches("# fetch hero\nquery { hero { name } }"));
    }

    @Test
    public void shouldHandleQueryWithDirectives() {
        GraphQLBody body = GraphQLBody.graphQL("query GetHero @cached { hero { name } }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_SUBSET);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertTrue(matcher.matches("query GetHero @cached { hero { name } extra { data } }"));
    }

    @Test
    public void shouldHandleSubscription() {
        GraphQLBody body = GraphQLBody.graphQL("subscription OnUpdate { userUpdated }")
            .withSelectionSetMatchType(SelectionSetMatchType.AST_EXACT);
        GraphQLAstMatcher matcher = new GraphQLAstMatcher(body);
        assertTrue(matcher.matches("subscription OnUpdate { userUpdated { id name } }"));
        assertFalse(matcher.matches("query OnUpdate { userUpdated { id } }"));
    }

    @Test
    public void shouldHandleFieldsWithAliases() {
        // Aliases appear before the colon: "smallPic: profilePic(size: 64)"
        // The parser picks up both the alias and the field name since it splits on non-alphanumeric
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { smallPic: profilePic(size: 64) { url } }");
        // "smallPic" and "profilePic" are both extracted since the parser reads identifiers
        assertTrue(fields.contains("smallPic"));
    }

    @Test
    public void shouldHandleFieldsWithStringArguments() {
        Set<String> fields = GraphQLAstMatcher.extractTopLevelFields("query { search(query: \"hello world\") { results } }");
        assertTrue(fields.contains("search"));
        assertFalse(fields.contains("hello"));
        assertFalse(fields.contains("world"));
    }
}
