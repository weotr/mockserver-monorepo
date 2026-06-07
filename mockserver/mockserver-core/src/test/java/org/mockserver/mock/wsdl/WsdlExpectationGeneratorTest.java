package org.mockserver.mock.wsdl;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.XPathBody;

import java.util.List;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class WsdlExpectationGeneratorTest {

    private final WsdlExpectationGenerator generator = new WsdlExpectationGenerator();

    private static final String SOAP11_CALCULATOR_WSDL =
        "<?xml version=\"1.0\"?>\n" +
            "<definitions name=\"Calculator\"\n" +
            "  targetNamespace=\"http://example.com/calc\"\n" +
            "  xmlns:tns=\"http://example.com/calc\"\n" +
            "  xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap/\"\n" +
            "  xmlns=\"http://schemas.xmlsoap.org/wsdl/\">\n" +
            "  <portType name=\"CalcPortType\">\n" +
            "    <operation name=\"Add\"><input message=\"tns:AddReq\"/><output message=\"tns:AddResp\"/></operation>\n" +
            "    <operation name=\"Subtract\"><input message=\"tns:SubReq\"/><output message=\"tns:SubResp\"/></operation>\n" +
            "  </portType>\n" +
            "  <binding name=\"CalcBinding\" type=\"tns:CalcPortType\">\n" +
            "    <soap:binding style=\"document\" transport=\"http://schemas.xmlsoap.org/soap/http\"/>\n" +
            "    <operation name=\"Add\">\n" +
            "      <soap:operation soapAction=\"http://example.com/calc/Add\"/>\n" +
            "      <input><soap:body use=\"literal\"/></input>\n" +
            "      <output><soap:body use=\"literal\"/></output>\n" +
            "    </operation>\n" +
            "    <operation name=\"Subtract\">\n" +
            "      <soap:operation soapAction=\"http://example.com/calc/Subtract\"/>\n" +
            "      <input><soap:body use=\"literal\"/></input>\n" +
            "      <output><soap:body use=\"literal\"/></output>\n" +
            "    </operation>\n" +
            "  </binding>\n" +
            "  <service name=\"CalculatorService\">\n" +
            "    <port name=\"CalcPort\" binding=\"tns:CalcBinding\">\n" +
            "      <soap:address location=\"http://localhost:8080/calculator\"/>\n" +
            "    </port>\n" +
            "  </service>\n" +
            "</definitions>";

    @Test
    public void generatesOneExpectationPerSoap11Operation() {
        List<Expectation> expectations = generator.generate(SOAP11_CALCULATOR_WSDL);

        assertEquals(2, expectations.size());

        Expectation add = expectations.get(0);
        assertEquals("CalculatorService.Add", add.getId());
        HttpRequest request = (HttpRequest) add.getHttpRequest();
        assertEquals("POST", request.getMethod().getValue());
        assertEquals("/calculator", request.getPath().getValue());
        assertEquals(
            "\"?" + Pattern.quote("http://example.com/calc/Add") + "\"?",
            request.getFirstHeader("SOAPAction")
        );
    }

    @Test
    public void generatesSkeletonSoap11ResponseEnvelope() {
        Expectation add = generator.generate(SOAP11_CALCULATOR_WSDL).get(0);
        HttpResponse response = add.getHttpResponse();

        assertEquals(Integer.valueOf(200), response.getStatusCode());
        assertEquals("text/xml; charset=utf-8", response.getFirstHeader("content-type"));
        String body = response.getBodyAsString();
        assertTrue(body, body.contains("http://schemas.xmlsoap.org/soap/envelope/"));
        assertTrue(body, body.contains("<AddResponse xmlns=\"http://example.com/calc\"/>"));
    }

    @Test
    public void generatesSoap12RequestAndResponse() {
        String soap12Wsdl = SOAP11_CALCULATOR_WSDL.replace(
            "http://schemas.xmlsoap.org/wsdl/soap/",
            "http://schemas.xmlsoap.org/wsdl/soap12/"
        );

        Expectation add = generator.generate(soap12Wsdl).get(0);

        // SOAP 1.2 matches the action in the content-type parameter, not a SOAPAction header
        HttpRequest request = (HttpRequest) add.getHttpRequest();
        assertTrue(isBlank(request.getFirstHeader("SOAPAction")));
        String contentTypeMatcher = request.getFirstHeader("content-type");
        assertTrue(contentTypeMatcher, contentTypeMatcher.startsWith("application/soap\\+xml"));
        assertTrue(contentTypeMatcher, contentTypeMatcher.contains(Pattern.quote("http://example.com/calc/Add")));

        HttpResponse response = add.getHttpResponse();
        assertEquals("application/soap+xml; charset=utf-8", response.getFirstHeader("content-type"));
        assertTrue(response.getBodyAsString().contains("http://www.w3.org/2003/05/soap-envelope"));
    }

    @Test
    public void fallsBackToBodyXPathWhenNoSoapAction() {
        String noActionWsdl = SOAP11_CALCULATOR_WSDL.replace(" soapAction=\"http://example.com/calc/Add\"", "");
        // Subtract still has its action; Add now has none
        Expectation add = generator.generate(noActionWsdl).get(0);

        HttpRequest request = (HttpRequest) add.getHttpRequest();
        assertTrue(isBlank(request.getFirstHeader("SOAPAction")));
        assertTrue(request.getBody() instanceof XPathBody);
        assertEquals("//*[local-name()='Add']", ((XPathBody) request.getBody()).getValue());
    }

    @Test
    public void rejectsBlankWsdl() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate("   "));
    }

    @Test
    public void rejectsNonXml() {
        assertThrows(IllegalArgumentException.class, () -> generator.generate("this is not xml"));
    }

    @Test
    public void rejectsWsdlWithNoOperations() {
        String empty =
            "<?xml version=\"1.0\"?>\n" +
                "<definitions targetNamespace=\"urn:x\" xmlns=\"http://schemas.xmlsoap.org/wsdl/\">\n" +
                "  <service name=\"Empty\"/>\n" +
                "</definitions>";
        assertThrows(IllegalArgumentException.class, () -> generator.generate(empty));
    }

    @Test
    public void soap12FallsBackToBodyXPathWhenNoSoapAction() {
        String soap12NoAction = SOAP11_CALCULATOR_WSDL
            .replace("http://schemas.xmlsoap.org/wsdl/soap/", "http://schemas.xmlsoap.org/wsdl/soap12/")
            .replace(" soapAction=\"http://example.com/calc/Add\"", "");

        HttpRequest request = (HttpRequest) generator.generate(soap12NoAction).get(0).getHttpRequest();
        assertTrue(isBlank(request.getFirstHeader("content-type")));
        assertTrue(request.getBody() instanceof XPathBody);
        assertEquals("//*[local-name()='Add']", ((XPathBody) request.getBody()).getValue());
    }

    @Test
    public void omitsXmlnsWhenNoTargetNamespace() {
        String noTargetNs = SOAP11_CALCULATOR_WSDL
            .replace("  targetNamespace=\"http://example.com/calc\"\n", "");

        String body = generator.generate(noTargetNs).get(0).getHttpResponse().getBodyAsString();
        assertTrue(body, body.contains("<AddResponse/>"));
    }

    @Test
    public void unparseableLocationFallsBackToRootPath() {
        String badLocation = SOAP11_CALCULATOR_WSDL
            .replace("http://localhost:8080/calculator", "ht tp://bad uri");

        HttpRequest request = (HttpRequest) generator.generate(badLocation).get(0).getHttpRequest();
        assertEquals("/", request.getPath().getValue());
    }

    @Test
    public void rejectsCraftedOperationName() {
        // a non-NCName operation name (single quote) must be rejected, not turned into a broken XPath
        String crafted = SOAP11_CALCULATOR_WSDL.replace("name=\"Add\"", "name=\"Add'injected\"");
        assertThrows(IllegalArgumentException.class, () -> generator.generate(crafted));
    }

    @Test
    public void escapesTargetNamespaceInResponseEnvelope() {
        String craftedNs = SOAP11_CALCULATOR_WSDL
            .replace("http://example.com/calc\"", "http://example.com/c&amp;\"");

        String body = generator.generate(craftedNs).get(0).getHttpResponse().getBodyAsString();
        // the literal ampersand from the namespace must be XML-escaped in the generated attribute
        assertTrue(body, body.contains("xmlns=\"http://example.com/c&amp;\""));
    }
}
