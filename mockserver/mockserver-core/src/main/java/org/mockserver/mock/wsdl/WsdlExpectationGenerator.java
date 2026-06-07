package org.mockserver.mock.wsdl;

import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.XPathBody;
import org.mockserver.xml.StringToXmlDocumentParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Generates MockServer {@link Expectation}s from a WSDL 1.1 document so SOAP web services
 * can be mocked without hand-authoring stubs. Supports SOAP 1.1 and SOAP 1.2 bindings.
 *
 * <p>For each {@code service/port} the binding is resolved and one expectation is produced
 * per binding operation:
 * <ul>
 *   <li>request: {@code POST} to the path of the {@code soap:address}/{@code soap12:address}
 *       location, matched by:
 *       <ul>
 *         <li>SOAP 1.1 — the {@code SOAPAction} header (tolerating optional surrounding quotes),
 *             when {@code soap:operation/@soapAction} is present;</li>
 *         <li>SOAP 1.2 — the {@code content-type} {@code action} parameter, when present;</li>
 *         <li>otherwise — a best-effort XPath on a body element whose local-name equals the
 *             operation name (documented heuristic, used only when no SOAP action is declared).</li>
 *       </ul>
 *   </li>
 *   <li>response: {@code 200} with a skeleton SOAP envelope containing a
 *       {@code <{Operation}Response/>} element in the WSDL target namespace.</li>
 * </ul>
 *
 * <p>The WSDL is parsed with the hardened {@link StringToXmlDocumentParser} (DOCTYPE and
 * external entities disabled) so importing an untrusted WSDL cannot trigger XXE.
 */
public class WsdlExpectationGenerator {

    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";
    private static final String SOAP11_BINDING_NS = "http://schemas.xmlsoap.org/wsdl/soap/";
    private static final String SOAP12_BINDING_NS = "http://schemas.xmlsoap.org/wsdl/soap12/";
    private static final String SOAP11_ENVELOPE_NS = "http://schemas.xmlsoap.org/soap/envelope/";
    private static final String SOAP12_ENVELOPE_NS = "http://www.w3.org/2003/05/soap-envelope";

    /**
     * Parses the given WSDL and returns one expectation per SOAP operation.
     *
     * @param wsdl the WSDL 1.1 document as a string
     * @return the generated expectations (never empty)
     * @throws IllegalArgumentException if the WSDL is blank, cannot be parsed, or declares no SOAP operations
     */
    public List<Expectation> generate(String wsdl) {
        if (wsdl == null || wsdl.trim().isEmpty()) {
            throw new IllegalArgumentException("WSDL is required");
        }
        final Document document = parse(wsdl);
        final Element definitions = document.getDocumentElement();
        if (definitions == null || !"definitions".equals(definitions.getLocalName())) {
            throw new IllegalArgumentException("not a WSDL document — expected a <definitions> root element");
        }
        final String targetNamespace = emptyToNull(definitions.getAttribute("targetNamespace"));

        final Map<String, Element> bindingsByName = new LinkedHashMap<>();
        final NodeList bindings = document.getElementsByTagNameNS(WSDL_NS, "binding");
        for (int i = 0; i < bindings.getLength(); i++) {
            final Element binding = (Element) bindings.item(i);
            bindingsByName.put(binding.getAttribute("name"), binding);
        }

        final List<Expectation> expectations = new ArrayList<>();
        final NodeList services = document.getElementsByTagNameNS(WSDL_NS, "service");
        for (int s = 0; s < services.getLength(); s++) {
            final Element service = (Element) services.item(s);
            final String serviceName = service.getAttribute("name");
            for (final Element port : childElements(service, WSDL_NS, "port")) {
                generatePortExpectations(port, serviceName, targetNamespace, bindingsByName, expectations);
            }
        }

        if (expectations.isEmpty()) {
            throw new IllegalArgumentException("no SOAP operations found in WSDL — no <service>/<port> bound to a SOAP binding with operations");
        }
        return expectations;
    }

    private void generatePortExpectations(Element port, String serviceName, String targetNamespace,
                                          Map<String, Element> bindingsByName, List<Expectation> expectations) {
        final String bindingName = localPart(port.getAttribute("binding"));
        final Element binding = bindingsByName.get(bindingName);
        if (binding == null) {
            return;
        }
        final boolean soap12 = !childElements(port, SOAP12_BINDING_NS, "address").isEmpty();
        final String location = addressLocation(port, soap12);
        final String path = pathFromLocation(location);
        final String bindingSoapNs = soap12 ? SOAP12_BINDING_NS : SOAP11_BINDING_NS;

        for (final Element operation : childElements(binding, WSDL_NS, "operation")) {
            final String operationName = operation.getAttribute("name");
            if (operationName == null || operationName.isEmpty()) {
                continue;
            }
            if (!isNCName(operationName)) {
                // operation names must be XML NCNames; reject crafted/invalid names rather than
                // silently emitting a broken XPath expression or an XML-injected response element
                throw new IllegalArgumentException("invalid SOAP operation name in WSDL (must be an XML NCName): '" + operationName + "'");
            }
            final String soapAction = soapActionOf(operation, bindingSoapNs);
            final Expectation expectation = new Expectation(
                buildRequest(path, soap12, soapAction, operationName)
            )
                .withId(serviceName + "." + operationName)
                .thenRespond(buildResponse(soap12, operationName, targetNamespace));
            expectations.add(expectation);
        }
    }

    private HttpRequest buildRequest(String path, boolean soap12, String soapAction, String operationName) {
        final HttpRequest httpRequest = request().withMethod("POST").withPath(path);
        if (soap12) {
            if (soapAction != null && !soapAction.isEmpty()) {
                // SOAP 1.2 carries the action in the content-type "action" parameter
                httpRequest.withHeader("content-type", "application/soap\\+xml.*action=\"?" + Pattern.quote(soapAction) + "\"?.*");
            } else {
                httpRequest.withBody(operationBody(operationName));
            }
        } else {
            if (soapAction != null && !soapAction.isEmpty()) {
                // SOAP 1.1 SOAPAction header, optionally quoted
                httpRequest.withHeader("SOAPAction", "\"?" + Pattern.quote(soapAction) + "\"?");
            } else {
                httpRequest.withBody(operationBody(operationName));
            }
        }
        return httpRequest;
    }

    private XPathBody operationBody(String operationName) {
        return XPathBody.xpath("//*[local-name()='" + operationName + "']");
    }

    private HttpResponse buildResponse(boolean soap12, String operationName, String targetNamespace) {
        final String envelopeNs = soap12 ? SOAP12_ENVELOPE_NS : SOAP11_ENVELOPE_NS;
        final String contentType = soap12 ? "application/soap+xml; charset=utf-8" : "text/xml; charset=utf-8";
        final String responseElement = targetNamespace == null
            ? "<" + operationName + "Response/>"
            : "<" + operationName + "Response xmlns=\"" + escapeXmlAttribute(targetNamespace) + "\"/>";
        final String envelope =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<soapenv:Envelope xmlns:soapenv=\"" + envelopeNs + "\">\n" +
                "  <soapenv:Body>\n" +
                "    " + responseElement + "\n" +
                "  </soapenv:Body>\n" +
                "</soapenv:Envelope>";
        return response()
            .withStatusCode(200)
            .withHeader("content-type", contentType)
            .withBody(envelope);
    }

    private String soapActionOf(Element bindingOperation, String bindingSoapNs) {
        for (final Element soapOperation : childElements(bindingOperation, bindingSoapNs, "operation")) {
            final String action = soapOperation.getAttribute("soapAction");
            if (action != null && !action.isEmpty()) {
                return action;
            }
        }
        return null;
    }

    private String addressLocation(Element port, boolean soap12) {
        final String ns = soap12 ? SOAP12_BINDING_NS : SOAP11_BINDING_NS;
        for (final Element address : childElements(port, ns, "address")) {
            final String location = address.getAttribute("location");
            if (location != null && !location.isEmpty()) {
                return location;
            }
        }
        return null;
    }

    private String pathFromLocation(String location) {
        if (location == null || location.isEmpty()) {
            return "/";
        }
        try {
            final String path = new URI(location).getPath();
            return (path == null || path.isEmpty()) ? "/" : path;
        } catch (Exception e) {
            return location.startsWith("/") ? location : "/";
        }
    }

    private Document parse(String wsdl) {
        try {
            return new StringToXmlDocumentParser().buildDocument(wsdl, (xmlAsString, exception, level) -> {
                // parse errors surface as a thrown SAXException below; nothing to record here
            }, true);
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse WSDL: " + e.getMessage(), e);
        }
    }

    private static List<Element> childElements(Element parent, String namespaceUri, String localName) {
        final List<Element> elements = new ArrayList<>();
        final NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            final Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE
                && namespaceUri.equals(node.getNamespaceURI())
                && localName.equals(node.getLocalName())) {
                elements.add((Element) node);
            }
        }
        return elements;
    }

    /**
     * Conservative XML NCName check (no colon, no quotes/angle-brackets). Used to reject crafted
     * operation names before they flow into an XPath expression or a generated response element.
     */
    private static boolean isNCName(String value) {
        return value.matches("[A-Za-z_][A-Za-z0-9._-]*");
    }

    /** Escapes a value for use inside a double-quoted XML attribute. */
    private static String escapeXmlAttribute(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private static String localPart(String qName) {
        if (qName == null) {
            return null;
        }
        final int colon = qName.indexOf(':');
        return colon >= 0 ? qName.substring(colon + 1) : qName;
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }
}
