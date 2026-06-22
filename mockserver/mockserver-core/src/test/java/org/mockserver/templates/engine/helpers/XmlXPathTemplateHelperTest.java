package org.mockserver.templates.engine.helpers;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class XmlXPathTemplateHelperTest {

    private final XmlXPathTemplateHelper helper = new XmlXPathTemplateHelper();

    @Test
    public void shouldEvaluateElementText() {
        String xml = "<order><id>12345</id><customer>Bob</customer></order>";
        assertThat(helper.evaluate(xml, "/order/id"), is("12345"));
        assertThat(helper.evaluate(xml, "/order/customer"), is("Bob"));
    }

    @Test
    public void shouldEvaluateAttribute() {
        String xml = "<order status=\"shipped\"><id>1</id></order>";
        assertThat(helper.evaluate(xml, "/order/@status"), is("shipped"));
    }

    @Test
    public void shouldEvaluateWithPredicate() {
        String xml = "<items><item id=\"1\">a</item><item id=\"2\">b</item></items>";
        assertThat(helper.evaluate(xml, "/items/item[@id='2']"), is("b"));
    }

    @Test
    public void shouldReturnEmptyForMissingNode() {
        String xml = "<order><id>1</id></order>";
        assertThat(helper.evaluate(xml, "/order/missing"), is(""));
    }

    @Test
    public void shouldReturnEmptyForInvalidXml() {
        assertThat(helper.evaluate("not xml <", "/x"), is(""));
    }

    @Test
    public void shouldReturnEmptyForNullInputs() {
        assertThat(helper.evaluate(null, "/x"), is(""));
        assertThat(helper.evaluate("<x/>", null), is(""));
    }

    @Test
    public void shouldNotResolveExternalEntitiesXXE() {
        // a DOCTYPE with an external entity must not be expanded; the parser
        // rejects the DOCTYPE entirely, so evaluation returns empty
        String xml = "<?xml version=\"1.0\"?>"
            + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
            + "<foo>&xxe;</foo>";
        assertThat(helper.evaluate(xml, "/foo"), is(""));
    }

    @Test
    public void shouldBeRegisteredInTemplateFunctions() {
        Object xpathHelper = org.mockserver.templates.engine.TemplateFunctions.BUILT_IN_HELPERS.get("xpath");
        assertThat(xpathHelper, is(notNullValue()));
        assertThat(xpathHelper, instanceOf(XmlXPathTemplateHelper.class));
    }
}
