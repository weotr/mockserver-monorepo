package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.mockserver.model.NottableString.string;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public class XmlStringMatcherTest {

    @Test
    public void shouldMatchMatchingXML() {
        String matched = "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>";
        assertThat(new XmlStringMatcher(new MockServerLogger(), "<element><key>some_key</key><value>some_value</value></element>").matches(matched), is(true));
        assertThat(new XmlStringMatcher(new MockServerLogger(), "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>").matches(matched), is(true));
    }

    @Test
    public void shouldMatchMatchingXMLIgnoringOrder() {
        String matched = "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>";
        assertThat(new XmlStringMatcher(new MockServerLogger(), "<element><value>some_value</value><key>some_key</key></element>").matches(matched), is(true));
        assertThat(new XmlStringMatcher(new MockServerLogger(), "" +
            "<element>" +
            "   <value>some_value</value>" +
            "   <key>some_key</key>" +
            "</element>").matches(matched), is(true));
    }

    @Test
    public void shouldMatchMatchingXMLWithIsNumberPlaceholder() {
        String matched = "" +
            "<message>" + NEW_LINE +
            "  <id>67890</id>" + NEW_LINE +
            "  <content>Hello</content>" + NEW_LINE +
            "</message>";
        assertThat(new XmlStringMatcher(new MockServerLogger(), "" +
            "<message>" + NEW_LINE +
            "  <id>${xmlunit.isNumber}</id>" + NEW_LINE +
            "  <content>Hello</content>" + NEW_LINE +
            "</message>").matches(matched), is(true));
    }

    @Test
    public void shouldNotMatchMatchingXMLWithIsNumberPlaceholder() {
        String matched = "" +
            "<message>" + NEW_LINE +
            "  <id>foo</id>" + NEW_LINE +
            "  <content>Hello</content>" + NEW_LINE +
            "</message>";
        assertThat(new XmlStringMatcher(new MockServerLogger(), "" +
            "<message>" + NEW_LINE +
            "  <id>${xmlunit.isNumber}</id>" + NEW_LINE +
            "  <content>Hello</content>" + NEW_LINE +
            "</message>").matches(matched), is(false));
    }

    @Test
    public void shouldMatchMatchingXMLWithIgnorePlaceholder() {
        String matched = "" +
            "<message>" + NEW_LINE +
            "  <id>67890</id>" + NEW_LINE +
            "  <content>Hello</content>" + NEW_LINE +
            "</message>";
        assertThat(new XmlStringMatcher(new MockServerLogger(), "" +
            "<message>" + NEW_LINE +
            "  <id>${xmlunit.ignore}</id>" + NEW_LINE +
            "  <content>Hello</content>" + NEW_LINE +
            "</message>").matches(matched), is(true));
    }

    @Test
    public void shouldMatchMatchingXMLWithDifferentNamespaceOrders() {
        String matched = "" +
            "<?xml version=\"1.0\"?>" + NEW_LINE +
            NEW_LINE +
            "<soap:Envelope" + NEW_LINE +
            "xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope/\"" + NEW_LINE +
            "soap:encodingStyle=\"http://www.w3.org/2003/05/soap-encoding\">" + NEW_LINE +
            NEW_LINE +
            "<soap:Body xmlns:m=\"http://www.example.org/stock\">" + NEW_LINE +
            "  <m:GetStockPriceResponse>" + NEW_LINE +
            "    <m:Price>34.5</m:Price>" + NEW_LINE +
            "  </m:GetStockPriceResponse>" + NEW_LINE +
            "</soap:Body>" + NEW_LINE +
            NEW_LINE +
            "</soap:Envelope>";
        assertThat(new XmlStringMatcher(new MockServerLogger(), "" +
            "<?xml version=\"1.0\"?>" + NEW_LINE +
            "<soap:Envelope" + NEW_LINE +
            "soap:encodingStyle=\"http://www.w3.org/2003/05/soap-encoding\"" + NEW_LINE +
            "xmlns:soap=\"http://www.w3.org/2003/05/soap-envelope/\"" + NEW_LINE +
            "xmlns:m=\"http://www.example.org/stock\">" + NEW_LINE +
            "<soap:Body>" + NEW_LINE +
            "  <m:GetStockPriceResponse>" + NEW_LINE +
            "    <m:Price>34.5</m:Price>" + NEW_LINE +
            "  </m:GetStockPriceResponse>" + NEW_LINE +
            "</soap:Body>" + NEW_LINE +
            "</soap:Envelope>").matches(matched), is(true));
    }

    @Test
    public void shouldMatchMatchingXMLWithDifferentNamespacePrefixes() {
        String matcher = "<a:element xmlns:a=\"the_namespace\"><a:key>some_key</a:key><a:value>some_value</a:value></a:element>";
        String matched = "<b:element xmlns:b=\"the_namespace\"><b:key>some_key</b:key><b:value>some_value</b:value></b:element>";
        assertThat(new XmlStringMatcher(new MockServerLogger(), matcher).matches(matched), is(true));
    }

    @Test
    public void shouldNotMatchMatchingXMLWithNot() {
        String matched = "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>";
        assertThat(notMatcher(new XmlStringMatcher(new MockServerLogger(), "<element><key>some_key</key><value>some_value</value></element>")).matches(matched), is(false));
        assertThat(notMatcher(new XmlStringMatcher(new MockServerLogger(), "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>")).matches(matched), is(false));
    }

    @Test
    public void shouldMatchMatchingXMLWithDifferentAttributeOrder() {
        String matched = "" +
            "<element attributeOne=\"one\" attributeTwo=\"two\">" +
            "   <key attributeOne=\"one\" attributeTwo=\"two\">some_key</key>" +
            "   <value>some_value</value>" +
            "</element>";
        assertThat(new XmlStringMatcher(new MockServerLogger(), "<element attributeTwo=\"two\" attributeOne=\"one\"><key attributeTwo=\"two\" attributeOne=\"one\">some_key</key><value>some_value</value></element>").matches(matched), is(true));
        assertThat(new XmlStringMatcher(new MockServerLogger(), "<element attributeTwo=\"two\" attributeOne=\"one\">" +
            "   <key attributeTwo=\"two\" attributeOne=\"one\">some_key</key>" +
            "   <value>some_value</value>" +
            "</element>").matches(matched), is(true));
    }

    @Test
    public void shouldNotMatchInvalidXml() {
        assertThat(new XmlStringMatcher(new MockServerLogger(), "invalid xml").matches("<element></element>"), is(false));
        assertThat(new XmlStringMatcher(new MockServerLogger(), "<element></element>").matches("invalid_xml"), is(false));
    }

    @Test
    public void shouldNotMatchNullExpectation() {
        assertThat(new XmlStringMatcher(new MockServerLogger(), string(null)).matches("some_value"), is(false));
        assertThat(new XmlStringMatcher(new MockServerLogger(), (String) null).matches("some_value"), is(false));
    }

    @Test
    public void shouldNotMatchEmptyExpectation() {
        assertThat(new XmlStringMatcher(new MockServerLogger(), "").matches("some_value"), is(false));
    }

    @Test
    public void shouldNotMatchNotMatchingXML() {
        String matched = "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>";
        assertThat(new XmlStringMatcher(new MockServerLogger(), "" +
            "<another_element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</another_element>").matches(matched), is(false));
        assertThat(new XmlStringMatcher(new MockServerLogger(), "" +
            "<element>" +
            "   <another_key>some_key</another_key>" +
            "   <value>some_value</value>" +
            "</element>").matches(matched), is(false));
        assertThat(new XmlStringMatcher(new MockServerLogger(), "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <another_value>some_value</another_value>" +
            "</element>").matches(matched), is(false));
        assertThat(new XmlStringMatcher(new MockServerLogger(), "" +
            "<element>" +
            "   <key>some_other_key</key>" +
            "   <value>some_value</value>" +
            "</element>").matches(matched), is(false));
    }

    @Test
    public void shouldMatchNotMatchingXMLWithNot() {
        String matched = "" +
            "<element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>";
        assertThat(notMatcher(new XmlStringMatcher(new MockServerLogger(), "" +
            "<another_element>" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</another_element>")).matches(matched), is(true));
        assertThat(notMatcher(new XmlStringMatcher(new MockServerLogger(), "" +
            "<element>" +
            "   <another_key>some_key</another_key>" +
            "   <value>some_value</value>" +
            "</element>")).matches(matched), is(true));
    }

    @Test
    public void shouldNotMatchXMLWithDifferentAttributes() {
        String matched = "" +
            "<element someAttribute=\"some_value\">" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>";
        assertThat(new XmlStringMatcher(new MockServerLogger(), "" +
            "<element someOtherAttribute=\"some_value\">" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>").matches(matched), is(false));
        assertThat(new XmlStringMatcher(new MockServerLogger(), "" +
            "<element someAttribute=\"some_other_value\">" +
            "   <key>some_key</key>" +
            "   <value>some_value</value>" +
            "</element>").matches(matched), is(false));
    }

    @Test
    public void shouldNotMatchNullTest() {
        assertThat(new XmlStringMatcher(new MockServerLogger(), "some_value").matches(null, null), is(false));
        assertThat(new XmlStringMatcher(new MockServerLogger(), "some_value").matches(null), is(false));
    }

    @Test
    public void shouldMatchNullTest() {
        assertThat(notMatcher(new XmlStringMatcher(new MockServerLogger(), "some_value")).matches(null, null), is(true));
        assertThat(notMatcher(new XmlStringMatcher(new MockServerLogger(), "some_value")).matches(null), is(true));
    }

    @Test
    public void shouldNotMatchEmptyTest() {
        assertThat(new XmlStringMatcher(new MockServerLogger(), "some_value").matches(""), is(false));
    }

    @Test
    public void showHaveCorrectEqualsBehaviour() {
        MockServerLogger mockServerLogger = new MockServerLogger();
        assertThat(new XmlStringMatcher(mockServerLogger, "some_value"), is(new XmlStringMatcher(mockServerLogger, "some_value")));
    }

    @Test
    public void shouldMatchCorrectlyUnderConcurrency() throws ExecutionException, InterruptedException {
        XmlStringMatcher matcherOne = new XmlStringMatcher(new MockServerLogger(), "<a>1</a>");
        XmlStringMatcher matcherTwo = new XmlStringMatcher(new MockServerLogger(), "<a>2</a>");

        ExecutorService executor = Executors.newFixedThreadPool(30);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                futures.add(executor.submit(() -> {
                    assertThat("matcherOne should match <a>1</a>", matcherOne.matches("<a>1</a>"), is(true));
                    assertThat("matcherOne should NOT match <a>2</a>", matcherOne.matches("<a>2</a>"), is(false));
                    assertThat("matcherTwo should match <a>2</a>", matcherTwo.matches("<a>2</a>"), is(true));
                    assertThat("matcherTwo should NOT match <a>1</a>", matcherTwo.matches("<a>1</a>"), is(false));
                    return true;
                }));
            }
            for (Future<Boolean> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
