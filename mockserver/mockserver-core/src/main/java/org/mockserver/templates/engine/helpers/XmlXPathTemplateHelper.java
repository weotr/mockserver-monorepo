package org.mockserver.templates.engine.helpers;

import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * XPath evaluation helper for templates: evaluate an XPath expression against an
 * XML document and return the string result. Uses the JDK {@code javax.xml.xpath}
 * APIs (these stay in the {@code javax} namespace per the Java compatibility
 * policy — they are JDK classes, not the jakarta-migrated EE ones).
 * <p>
 * The XML parser is hardened against XXE by disabling DOCTYPE declarations and
 * external entity resolution.
 */
public class XmlXPathTemplateHelper {

    /**
     * Evaluates the given XPath expression against the supplied XML string and
     * returns the result as a string. Returns an empty string for {@code null}
     * inputs or when the document cannot be parsed / the expression is invalid.
     */
    public String evaluate(String xml, String xpathExpression) {
        if (xml == null || xpathExpression == null) {
            return "";
        }
        try {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            // harden against XXE
            documentBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            documentBuilderFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            documentBuilderFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            documentBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            documentBuilderFactory.setXIncludeAware(false);
            documentBuilderFactory.setExpandEntityReferences(false);
            documentBuilderFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            documentBuilderFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            documentBuilderFactory.setNamespaceAware(true);

            DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
            Document document = documentBuilder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            XPathFactory xPathFactory = XPathFactory.newInstance();
            xPathFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            XPath xPath = xPathFactory.newXPath();
            String result = (String) xPath.evaluate(xpathExpression, document, XPathConstants.STRING);
            return result != null ? result : "";
        } catch (Exception exception) {
            return "";
        }
    }

    @Override
    public String toString() {
        return "XmlXPathTemplateHelper";
    }
}
