package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import com.google.common.collect.ImmutableMap;

import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class XPathMatcherTest {

    @Test
    public void shouldMatchMatchingXPath() {
        String matched = "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>";
        assertThat(new XPathMatcher(new MockServerLogger(), "/element[key = 'some_key' and value = 'some_value']").matches(null, matched), is(true));
        assertThat(new XPathMatcher(new MockServerLogger(), "/element[key = 'some_key']").matches(null, matched), is(true));
        assertThat(new XPathMatcher(new MockServerLogger(), "/element/key").matches(null, matched), is(true));
        assertThat(new XPathMatcher(new MockServerLogger(), "/element[key and value]").matches(null, matched), is(true));
    }

    @Test
    public void shouldNotMatchMatchingXPathWithNot() {
        String matched = "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>";
        assertThat(notMatcher(new XPathMatcher(new MockServerLogger(), "/element[key = 'some_key' and value = 'some_value']")).matches(null, matched), is(false));
        assertThat(notMatcher(new XPathMatcher(new MockServerLogger(), "/element[key = 'some_key']")).matches(null, matched), is(false));
        assertThat(notMatcher(new XPathMatcher(new MockServerLogger(), "/element/key")).matches(null, matched), is(false));
        assertThat(notMatcher(new XPathMatcher(new MockServerLogger(), "/element[key and value]")).matches(null, matched), is(false));
    }

    @Test
    public void shouldMatchMatchingString() {
        assertThat(new XPathMatcher(new MockServerLogger(), "some_value").matches(null, "some_value"), is(true));
        assertThat(new XPathMatcher(new MockServerLogger(), "some_value").matches(null, "some_other_value"), is(false));
    }

    @Test
    public void shouldNotMatchNullExpectation() {
        assertThat(new XPathMatcher(new MockServerLogger(), null).matches(null, "some_value"), is(false));
    }

    @Test
    public void shouldNotMatchEmptyExpectation() {
        assertThat(new XPathMatcher(new MockServerLogger(), "").matches(null, "some_value"), is(false));
    }

    @Test
    public void shouldNotMatchNotMatchingXPath() {
        String matched = "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>";
        assertThat(new XPathMatcher(new MockServerLogger(), "/element[key = 'some_key' and value = 'some_other_value']").matches(null, matched), is(false));
        assertThat(new XPathMatcher(new MockServerLogger(), "/element[key = 'some_other_key']").matches(null, matched), is(false));
        assertThat(new XPathMatcher(new MockServerLogger(), "/element/not_key").matches(null, matched), is(false));
        assertThat(new XPathMatcher(new MockServerLogger(), "/element[key and not_value]").matches(null, matched), is(false));
    }

    @Test
    public void shouldMatchNotMatchingXPathWithNot() {
        String matched = "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>";
        assertThat(notMatcher(new XPathMatcher(new MockServerLogger(), "/element[key = 'some_key' and value = 'some_other_value']")).matches(null, matched), is(true));
        assertThat(notMatcher(new XPathMatcher(new MockServerLogger(), "/element[key = 'some_other_key']")).matches(null, matched), is(true));
        assertThat(notMatcher(new XPathMatcher(new MockServerLogger(), "/element/not_key")).matches(null, matched), is(true));
        assertThat(notMatcher(new XPathMatcher(new MockServerLogger(), "/element[key and not_value]")).matches(null, matched), is(true));
    }

    @Test
    public void shouldNotMatchNullTest() {
        assertThat(new XPathMatcher(new MockServerLogger(), "some_value").matches(null, null), is(false));
    }

    @Test
    public void shouldNotMatchEmptyTest() {
        assertThat(new XPathMatcher(new MockServerLogger(), "some_value").matches(null, ""), is(false));
    }

    @Test
    public void showHaveCorrectEqualsBehaviour() {
        MockServerLogger mockServerLogger = new MockServerLogger();
        assertThat(new XPathMatcher(mockServerLogger, "some_value"), is(new XPathMatcher(mockServerLogger, "some_value")));
    }

    @Test
    public void shouldMatchMatchingXPathWithNamespaces() {
        String matched = "" +
                "<foo:root xmlns:foo='http://foo.example.com' xmlns:bar='http://bar.example.com'>" +
                "   <bar:content>some_key</bar:content>" +
                "</foo:root>";
        assertThat(new XPathMatcher(new MockServerLogger(),"//content").matches(null, matched), is(false));
        assertThat(new XPathMatcher(new MockServerLogger(),"//*[local-name()='content']").matches(null, matched), is(true));

        // xml is not parsed namespace aware, so this should fail
        assertThat(new XPathMatcher(new MockServerLogger(),"//*[local-name()='content' and namespace-uri()='http://bar.example.com']").matches(null, matched), is(false));
        
        // when using namespace prefixes, xml is parsed as namespace aware
        assertThat(new XPathMatcher(new MockServerLogger(),"//bar:content", ImmutableMap.of("bar","http://bar.example.com")).matches(null, matched), is(true));      
    }

}
