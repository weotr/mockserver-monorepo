package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.HttpRequest.request;

/**
 * @author jamesdbloom
 */
public class XmlSchemaMatcherTest {

    private final String XML_SCHEMA = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + NEW_LINE +
        "<xs:schema xmlns:xs=\"http://www.w3.org/2001/XMLSchema\" elementFormDefault=\"qualified\" attributeFormDefault=\"unqualified\">" + NEW_LINE +
        "    <!-- XML Schema Generated from XML Document on Wed Jun 28 2017 21:52:45 GMT+0100 (BST) -->" + NEW_LINE +
        "    <!-- with XmlGrid.net Free Online Service http://xmlgrid.net -->" + NEW_LINE +
        "    <xs:element name=\"notes\">" + NEW_LINE +
        "        <xs:complexType>" + NEW_LINE +
        "            <xs:sequence>" + NEW_LINE +
        "                <xs:element name=\"note\" maxOccurs=\"unbounded\">" + NEW_LINE +
        "                    <xs:complexType>" + NEW_LINE +
        "                        <xs:sequence>" + NEW_LINE +
        "                            <xs:element name=\"to\" type=\"xs:string\"></xs:element>" + NEW_LINE +
        "                            <xs:element name=\"from\" type=\"xs:string\"></xs:element>" + NEW_LINE +
        "                            <xs:element name=\"heading\" type=\"xs:string\"></xs:element>" + NEW_LINE +
        "                            <xs:element name=\"body\" type=\"xs:string\"></xs:element>" + NEW_LINE +
        "                        </xs:sequence>" + NEW_LINE +
        "                    </xs:complexType>" + NEW_LINE +
        "                </xs:element>" + NEW_LINE +
        "            </xs:sequence>" + NEW_LINE +
        "        </xs:complexType>" + NEW_LINE +
        "    </xs:element>" + NEW_LINE +
        "</xs:schema>";

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    @Test
    public void shouldMatchValidXml() {
        // given
        XmlSchemaMatcher matcher = new XmlSchemaMatcher(mockServerLogger, XML_SCHEMA);
        String validXml = "<notes>" +
            "<note>" +
            "<to>Bob</to>" +
            "<from>Alice</from>" +
            "<heading>Reminder</heading>" +
            "<body>Don't forget the meeting</body>" +
            "</note>" +
            "</notes>";

        // then - valid document matches
        assertThat("valid XML with all required child elements should match schema", matcher.matches(null, validXml), is(true));
    }

    @Test
    public void shouldMatchValidXmlWithMultipleNotes() {
        // given
        XmlSchemaMatcher matcher = new XmlSchemaMatcher(mockServerLogger, XML_SCHEMA);
        String validXml = "<notes>" +
            "<note>" +
            "<to>Bob</to>" +
            "<from>Alice</from>" +
            "<heading>Reminder</heading>" +
            "<body>Meeting at 3pm</body>" +
            "</note>" +
            "<note>" +
            "<to>Charlie</to>" +
            "<from>Dave</from>" +
            "<heading>Update</heading>" +
            "<body>Project status</body>" +
            "</note>" +
            "</notes>";

        // then
        assertThat("valid XML with multiple note elements should match schema", matcher.matches(null, validXml), is(true));
    }

    @Test
    public void shouldNotMatchInvalidXml() {
        // given - missing required child elements
        XmlSchemaMatcher matcher = new XmlSchemaMatcher(mockServerLogger, XML_SCHEMA);
        String invalidXml = "<notes><note><to>Bob</to></note></notes>";

        // when
        assertThat("XML missing required elements (from, heading, body) should not match", matcher.matches(new MatchDifference(false, request()), invalidXml), is(false));
    }

    @Test
    public void shouldNotMatchXmlWithWrongRootElement() {
        // given - root element is not "notes"
        XmlSchemaMatcher matcher = new XmlSchemaMatcher(mockServerLogger, XML_SCHEMA);
        String invalidXml = "<wrong><to>Bob</to></wrong>";

        // when
        assertThat("XML with wrong root element 'wrong' instead of 'notes' should not match", matcher.matches(new MatchDifference(false, request()), invalidXml), is(false));
    }

    @Test
    public void shouldNotMatchEmptyString() {
        // given
        XmlSchemaMatcher matcher = new XmlSchemaMatcher(mockServerLogger, XML_SCHEMA);

        // then - blank string does not match
        assertThat("empty string should not match XML schema", matcher.matches(new MatchDifference(false, request()), ""), is(false));
    }

    @Test
    public void shouldNotMatchNull() {
        // given
        XmlSchemaMatcher matcher = new XmlSchemaMatcher(mockServerLogger, XML_SCHEMA);

        // then - null does not match
        assertThat("null should not match XML schema", matcher.matches(new MatchDifference(false, request()), null), is(false));
    }

    @Test
    public void shouldRecordDifferenceWhenNotMatching() {
        // given
        XmlSchemaMatcher matcher = new XmlSchemaMatcher(mockServerLogger, XML_SCHEMA);
        String invalidXml = "<notes><note><to>Bob</to></note></notes>";
        MatchDifference matchDifference = new MatchDifference(true, request());
        matchDifference.currentField(MatchDifference.Field.BODY);

        // when
        assertThat("invalid XML should not match schema", matcher.matches(matchDifference, invalidXml), is(false));

        // then - a difference was recorded containing schema-related diagnostic
        assertThat("match differences should be recorded for BODY field", matchDifference.getDifferences(MatchDifference.Field.BODY).isEmpty(), is(false));
    }

    @Test
    public void shouldHandleNonXmlInput() {
        // given - completely non-XML input that will cause a parsing exception
        XmlSchemaMatcher matcher = new XmlSchemaMatcher(mockServerLogger, XML_SCHEMA);
        String nonXml = "this is not xml at all";
        MatchDifference matchDifference = new MatchDifference(true, request());
        matchDifference.currentField(MatchDifference.Field.BODY);

        // when - the matcher catches the exception and returns false
        assertThat("non-XML input should not match XML schema", matcher.matches(matchDifference, nonXml), is(false));

        // then - a difference was recorded
        assertThat("match differences should be recorded for non-XML input", matchDifference.getDifferences(MatchDifference.Field.BODY).isEmpty(), is(false));
    }

    @Test
    public void showHaveCorrectEqualsBehaviour() {
        MockServerLogger mockServerLogger = new MockServerLogger();
        assertEquals(new XmlSchemaMatcher(mockServerLogger, XML_SCHEMA), new XmlSchemaMatcher(mockServerLogger, XML_SCHEMA));
    }
}
