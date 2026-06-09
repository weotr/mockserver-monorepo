package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class JsonPathMatcherTest {

    @Test
    public void shouldMatchMatchingJsonPath() {
        String matched = "" +
            "{" + NEW_LINE +
            "    \"store\": {" + NEW_LINE +
            "        \"book\": [" + NEW_LINE +
            "            {" + NEW_LINE +
            "                \"category\": \"reference\"," + NEW_LINE +
            "                \"author\": \"Nigel Rees\"," + NEW_LINE +
            "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
            "                \"price\": 8.95" + NEW_LINE +
            "            }," + NEW_LINE +
            "            {" + NEW_LINE +
            "                \"category\": \"fiction\"," + NEW_LINE +
            "                \"author\": \"Herman Melville\"," + NEW_LINE +
            "                \"title\": \"Moby Dick\"," + NEW_LINE +
            "                \"isbn\": \"0-553-21311-3\"," + NEW_LINE +
            "                \"price\": 8.99" + NEW_LINE +
            "            }" + NEW_LINE +
            "        ]," + NEW_LINE +
            "        \"bicycle\": {" + NEW_LINE +
            "            \"color\": \"red\"," + NEW_LINE +
            "            \"price\": 19.95" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"expensive\": 10" + NEW_LINE +
            "}";
        assertThat(new JsonPathMatcher(new MockServerLogger(), "$..book[?(@.price <= $['expensive'])]").matches(null, matched), is(true));
        assertThat(new JsonPathMatcher(new MockServerLogger(), "$..book[?(@.isbn)]").matches(null, matched), is(true));
        assertThat(new JsonPathMatcher(new MockServerLogger(), "$..bicycle[?(@.color)]").matches(null, matched), is(true));
    }

    @Test
    public void shouldNotMatchMatchingJsonPathWithNot() {
        String matched = "" +
            "{" + NEW_LINE +
            "    \"store\": {" + NEW_LINE +
            "        \"book\": [" + NEW_LINE +
            "            {" + NEW_LINE +
            "                \"category\": \"reference\"," + NEW_LINE +
            "                \"author\": \"Nigel Rees\"," + NEW_LINE +
            "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
            "                \"price\": 8.95" + NEW_LINE +
            "            }," + NEW_LINE +
            "            {" + NEW_LINE +
            "                \"category\": \"fiction\"," + NEW_LINE +
            "                \"author\": \"Herman Melville\"," + NEW_LINE +
            "                \"title\": \"Moby Dick\"," + NEW_LINE +
            "                \"isbn\": \"0-553-21311-3\"," + NEW_LINE +
            "                \"price\": 8.99" + NEW_LINE +
            "            }" + NEW_LINE +
            "        ]," + NEW_LINE +
            "        \"bicycle\": {" + NEW_LINE +
            "            \"color\": \"red\"," + NEW_LINE +
            "            \"price\": 19.95" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"expensive\": 10" + NEW_LINE +
            "}";
        assertThat(notMatcher(new JsonPathMatcher(new MockServerLogger(), "$..book[?(@.price <= $['expensive'])]")).matches(null, matched), is(false));
        assertThat(notMatcher(new JsonPathMatcher(new MockServerLogger(), "$..book[?(@.isbn)]")).matches(null, matched), is(false));
        assertThat(notMatcher(new JsonPathMatcher(new MockServerLogger(), "$..bicycle[?(@.color)]")).matches(null, matched), is(false));
    }

    @Test
    public void shouldMatchMatchingString() {
        assertThat(new JsonPathMatcher(new MockServerLogger(), "some_value").matches(null, "some_value"), is(true));
        assertThat(new JsonPathMatcher(new MockServerLogger(), "some_value").matches(null, "some_other_value"), is(false));
    }

    @Test
    public void shouldNotMatchNullExpectation() {
        assertThat(new JsonPathMatcher(new MockServerLogger(), null).matches(null, "some_value"), is(false));
    }

    @Test
    public void shouldNotMatchEmptyExpectation() {
        assertThat(new JsonPathMatcher(new MockServerLogger(), "").matches(null, "some_value"), is(false));
    }

    @Test
    public void shouldNotMatchNotMatchingJsonPath() {
        String matched = "" +
            "{" + NEW_LINE +
            "    \"store\": {" + NEW_LINE +
            "        \"book\": [" + NEW_LINE +
            "            {" + NEW_LINE +
            "                \"category\": \"reference\"," + NEW_LINE +
            "                \"author\": \"Nigel Rees\"," + NEW_LINE +
            "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
            "                \"price\": 8.95" + NEW_LINE +
            "            }," + NEW_LINE +
            "            {" + NEW_LINE +
            "                \"category\": \"fiction\"," + NEW_LINE +
            "                \"author\": \"Herman Melville\"," + NEW_LINE +
            "                \"title\": \"Moby Dick\"," + NEW_LINE +
            "                \"isbn\": \"0-553-21311-3\"," + NEW_LINE +
            "                \"price\": 8.99" + NEW_LINE +
            "            }" + NEW_LINE +
            "        ]," + NEW_LINE +
            "        \"bicycle\": {" + NEW_LINE +
            "            \"color\": \"red\"," + NEW_LINE +
            "            \"price\": 19.95" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"expensive\": 10" + NEW_LINE +
            "}";
        assertThat(new JsonPathMatcher(new MockServerLogger(), "$..book[?(@.price > $['expensive'])]").matches(null, matched), is(false));
        assertThat(new JsonPathMatcher(new MockServerLogger(), "$..book[?(@.color)]").matches(null, matched), is(false));
        assertThat(new JsonPathMatcher(new MockServerLogger(), "$..bicycle[?(@.isbn)]").matches(null, matched), is(false));
    }

    @Test
    public void shouldMatchNotMatchingJsonPathWithNot() {
        String matched = "" +
            "{" + NEW_LINE +
            "    \"store\": {" + NEW_LINE +
            "        \"book\": [" + NEW_LINE +
            "            {" + NEW_LINE +
            "                \"category\": \"reference\"," + NEW_LINE +
            "                \"author\": \"Nigel Rees\"," + NEW_LINE +
            "                \"title\": \"Sayings of the Century\"," + NEW_LINE +
            "                \"price\": 8.95" + NEW_LINE +
            "            }," + NEW_LINE +
            "            {" + NEW_LINE +
            "                \"category\": \"fiction\"," + NEW_LINE +
            "                \"author\": \"Herman Melville\"," + NEW_LINE +
            "                \"title\": \"Moby Dick\"," + NEW_LINE +
            "                \"isbn\": \"0-553-21311-3\"," + NEW_LINE +
            "                \"price\": 8.99" + NEW_LINE +
            "            }" + NEW_LINE +
            "        ]," + NEW_LINE +
            "        \"bicycle\": {" + NEW_LINE +
            "            \"color\": \"red\"," + NEW_LINE +
            "            \"price\": 19.95" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }," + NEW_LINE +
            "    \"expensive\": 10" + NEW_LINE +
            "}";
        assertThat(notMatcher(new JsonPathMatcher(new MockServerLogger(), "$..book[?(@.price > $['expensive'])]")).matches(null, matched), is(true));
        assertThat(notMatcher(new JsonPathMatcher(new MockServerLogger(), "$..book[?(@.color)]")).matches(null, matched), is(true));
        assertThat(notMatcher(new JsonPathMatcher(new MockServerLogger(), "$..bicycle[?(@.isbn)]")).matches(null, matched), is(true));
    }

    @Test
    public void shouldNotMatchNullTest() {
        assertThat(new JsonPathMatcher(new MockServerLogger(), "some_value").matches(null, null), is(false));
    }

    @Test
    public void shouldNotMatchEmptyTest() {
        assertThat(new JsonPathMatcher(new MockServerLogger(), "some_value").matches(null, ""), is(false));
    }

    @Test
    public void showHaveCorrectEqualsBehaviour() {
        MockServerLogger mockServerLogger = new MockServerLogger();
        assertThat(new JsonPathMatcher(mockServerLogger, "some_value"), is(new JsonPathMatcher(mockServerLogger, "some_value")));
    }
}
