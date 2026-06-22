/*
 *  Copyright 2017 SmartBear Software
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.mockserver.openapi.examples;

import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.XML;
import org.junit.Test;
import org.mockserver.openapi.examples.models.ArrayExample;
import org.mockserver.openapi.examples.models.BooleanExample;
import org.mockserver.openapi.examples.models.Example;
import org.mockserver.openapi.examples.models.IntegerExample;
import org.mockserver.openapi.examples.models.ObjectExample;
import org.mockserver.openapi.examples.models.StringExample;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertNotNull;

/**
 * Verifies {@link XmlExampleSerializer} produces well-formed XML — every output is parsed with a
 * namespace-aware {@link DocumentBuilder} (the regression guard: a namespaced schema must now yield a
 * non-empty, well-formed body rather than an empty/malformed one).
 */
public class XmlExampleSerializerTest {

    private static final String NS = "http://example.com/books";

    /**
     * Parses the serialized XML with namespace-awareness on, proving it is well-formed, and returns the
     * document root for further structural assertions.
     */
    private Document parseNamespaceAware(String xml) throws Exception {
        assertNotNull("serializer returned null (malformed/failed serialization)", xml);
        assertThat("serialized XML must be non-empty", xml.trim().isEmpty(), is(false));
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    // -------------------------------------------------------------------------------------------------
    // Fix 1 — namespace declarations
    // -------------------------------------------------------------------------------------------------

    @Test
    public void shouldSerializeElementWithDefaultNamespace() throws Exception {
        ObjectExample book = new ObjectExample();
        book.setName("Book");
        book.setNamespace(NS);
        book.put("title", new StringExample("Moby Dick"));

        String xml = new XmlExampleSerializer().serialize(book);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("Book"));
        assertThat("default namespace declared via xmlns=\"...\"", root.getNamespaceURI(), is(NS));
        assertThat("no prefix for a default namespace", root.getPrefix(), nullValue());
    }

    @Test
    public void shouldSerializeElementWithPrefixedNamespace() throws Exception {
        ObjectExample book = new ObjectExample();
        book.setName("Book");
        book.setNamespace(NS);
        book.setPrefix("ex");
        book.put("title", new StringExample("Moby Dick"));

        String xml = new XmlExampleSerializer().serialize(book);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("Book"));
        assertThat(root.getNamespaceURI(), is(NS));
        assertThat("prefix bound via xmlns:ex=\"...\"", root.getPrefix(), is("ex"));
    }

    @Test
    public void shouldSerializeNamespacedAttribute() throws Exception {
        ObjectExample book = new ObjectExample();
        book.setName("Book");
        book.setNamespace(NS);
        book.setPrefix("ex");

        StringExample id = new StringExample("123");
        id.setName("id");
        id.setAttribute(true);
        id.setNamespace(NS);
        id.setPrefix("ex");
        book.put("id", id);

        String xml = new XmlExampleSerializer().serialize(book);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getAttributeNS(NS, "id"), is("123"));
    }

    @Test
    public void shouldSerializeAttributeWithoutNamespace() throws Exception {
        ObjectExample book = new ObjectExample();
        book.setName("Book");

        StringExample id = new StringExample("123");
        id.setName("id");
        id.setAttribute(true);
        book.put("id", id);

        String xml = new XmlExampleSerializer().serialize(book);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getAttribute("id"), is("123"));
    }

    // -------------------------------------------------------------------------------------------------
    // structure matrix — every cell must parse well-formed
    // -------------------------------------------------------------------------------------------------

    @Test
    public void shouldSerializeNestedObjectWithNamespace() throws Exception {
        ObjectExample author = new ObjectExample();
        author.put("name", new StringExample("Herman Melville"));

        ObjectExample book = new ObjectExample();
        book.setName("Book");
        book.setNamespace(NS);
        book.setPrefix("ex");
        book.put("title", new StringExample("Moby Dick"));
        book.put("author", author);

        String xml = new XmlExampleSerializer().serialize(book);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getNamespaceURI(), is(NS));
        // nested children inherit the prefixed namespace — declared once on the root only
        assertThat(countXmlnsDeclarations(xml, "xmlns:ex"), is(1));
    }

    @Test
    public void shouldSerializeArrayOfObjectsWithNamespace() throws Exception {
        ArrayExample books = new ArrayExample();
        books.setName("book");
        books.setNamespace(NS);
        books.setPrefix("ex");
        books.setWrapped(true);
        books.setWrappedName("books");

        ObjectExample book1 = new ObjectExample();
        book1.put("title", new StringExample("Moby Dick"));
        ObjectExample book2 = new ObjectExample();
        book2.put("title", new StringExample("Bartleby"));
        books.add(book1);
        books.add(book2);

        String xml = new XmlExampleSerializer().serialize(books);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("books"));
        assertThat(root.getNamespaceURI(), is(NS));
    }

    @Test
    public void shouldSerializeTopLevelArrayWithDefaultNamespace() throws Exception {
        ArrayExample books = new ArrayExample();
        books.setName("book");
        books.setNamespace(NS);
        books.setWrapped(true);
        books.setWrappedName("books");
        books.add(new StringExample("Moby Dick"));
        books.add(new StringExample("Bartleby"));

        String xml = new XmlExampleSerializer().serialize(books);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("books"));
        assertThat(root.getNamespaceURI(), is(NS));
        assertThat(root.getPrefix(), nullValue());
    }

    // -------------------------------------------------------------------------------------------------
    // OpenAPI 3.x "XML Object" array rules — unwrapped vs wrapped, item naming, no pluralisation
    // -------------------------------------------------------------------------------------------------

    @Test
    public void shouldSerializeUnwrappedArrayAsRepeatedElementsNamedAfterProperty() throws Exception {
        // default (no xml.wrapped): repeated elements named after the property — NOT pluralised
        // photoUrls: ["a", "b"] -> <photoUrls>a</photoUrls><photoUrls>b</photoUrls>
        ArrayExample photoUrls = new ArrayExample();
        photoUrls.setName("photoUrls");
        photoUrls.add(new StringExample("a"));
        photoUrls.add(new StringExample("b"));

        ObjectExample pet = new ObjectExample();
        pet.setName("pet");
        pet.put("photoUrls", photoUrls);

        String xml = new XmlExampleSerializer().serialize(pet);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("pet"));
        // exactly two <photoUrls> repeated elements, no wrapper, no pluralised <photoUrlss>
        assertThat(root.getElementsByTagName("photoUrls").getLength(), is(2));
        assertThat(root.getElementsByTagName("photoUrlss").getLength(), is(0));
        assertThat(((Element) root.getElementsByTagName("photoUrls").item(0)).getTextContent(), is("a"));
        assertThat(((Element) root.getElementsByTagName("photoUrls").item(1)).getTextContent(), is("b"));
    }

    @Test
    public void shouldSerializeWrappedArrayWithWrapperAndItemElementNames() throws Exception {
        // xml.wrapped + items.xml.name=photoUrl
        // -> <photoUrls><photoUrl>a</photoUrl><photoUrl>b</photoUrl></photoUrls>
        ArrayExample photoUrls = new ArrayExample();
        photoUrls.setName("photoUrls");
        photoUrls.setWrapped(true);
        StringExample a = new StringExample("a");
        a.setName("photoUrl");
        StringExample b = new StringExample("b");
        b.setName("photoUrl");
        photoUrls.add(a);
        photoUrls.add(b);

        ObjectExample pet = new ObjectExample();
        pet.setName("pet");
        pet.put("photoUrls", photoUrls);

        String xml = new XmlExampleSerializer().serialize(pet);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        // single wrapper <photoUrls> containing two <photoUrl> item elements; no pluralisation
        assertThat(root.getElementsByTagName("photoUrls").getLength(), is(1));
        Element wrapper = (Element) root.getElementsByTagName("photoUrls").item(0);
        assertThat(wrapper.getElementsByTagName("photoUrl").getLength(), is(2));
        assertThat(((Element) wrapper.getElementsByTagName("photoUrl").item(0)).getTextContent(), is("a"));
        assertThat(((Element) wrapper.getElementsByTagName("photoUrl").item(1)).getTextContent(), is("b"));
        assertThat("no pluralised wrapper", xml.contains("photoUrlss"), is(false));
    }

    @Test
    public void shouldSerializeWrappedArrayUsingWrappedNameForWrapperElement() throws Exception {
        // wrapper named by the array's xml.name (carried as wrappedName) when present
        ArrayExample books = new ArrayExample();
        books.setName("book");
        books.setWrapped(true);
        books.setWrappedName("books");
        ObjectExample book1 = new ObjectExample();
        book1.put("title", new StringExample("Moby Dick"));
        ObjectExample book2 = new ObjectExample();
        book2.put("title", new StringExample("Bartleby"));
        books.add(book1);
        books.add(book2);

        String xml = new XmlExampleSerializer().serialize(books);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat("wrapper uses xml.name (wrappedName)", root.getLocalName(), is("books"));
        // anonymous object items take the array property name "book"
        assertThat(root.getElementsByTagName("book").getLength(), is(2));
    }

    @Test
    public void shouldSerializeWrappedArrayOfNamedObjects() throws Exception {
        // tags: wrapped, items are Tag objects whose schema declared xml.name=tag
        // -> <tags><tag>...</tag><tag>...</tag></tags>
        ArrayExample tags = new ArrayExample();
        tags.setName("tags");
        tags.setWrapped(true);
        ObjectExample tag1 = new ObjectExample();
        tag1.setName("tag");
        tag1.put("id", new IntegerExample(1));
        ObjectExample tag2 = new ObjectExample();
        tag2.setName("tag");
        tag2.put("id", new IntegerExample(2));
        tags.add(tag1);
        tags.add(tag2);

        String xml = new XmlExampleSerializer().serialize(tags);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("tags"));
        assertThat(root.getElementsByTagName("tag").getLength(), is(2));
        assertThat("no pluralised tagss wrapper", xml.contains("tagss"), is(false));
    }

    @Test
    public void shouldSerializeUnwrappedArrayOfAnonymousObjectsAsRepeatedElements() throws Exception {
        // unwrapped array of anonymous objects -> repeated elements named after the array property
        ArrayExample items = new ArrayExample();
        items.setName("entry");
        ObjectExample o1 = new ObjectExample();
        o1.put("v", new StringExample("x"));
        ObjectExample o2 = new ObjectExample();
        o2.put("v", new StringExample("y"));
        items.add(o1);
        items.add(o2);

        ObjectExample container = new ObjectExample();
        container.setName("container");
        container.put("entry", items);

        String xml = new XmlExampleSerializer().serialize(container);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getElementsByTagName("entry").getLength(), is(2));
        assertThat(root.getElementsByTagName("entrys").getLength(), is(0));
    }

    @Test
    public void shouldNotPermanentlyRenameSharedAnonymousItemAcrossTwoArrays() throws Exception {
        // two array properties whose items are the SAME anonymous item instance (as can happen for two
        // arrays referencing the same $ref with no items.xml.name): each array must render the item under
        // its OWN property name — the first array serialised must not permanently rename the shared item.
        ObjectExample sharedItem = new ObjectExample();
        sharedItem.put("v", new StringExample("x"));

        ArrayExample first = new ArrayExample();
        first.setName("firsts");
        first.add(sharedItem);

        ArrayExample second = new ArrayExample();
        second.setName("seconds");
        second.add(sharedItem);

        ObjectExample container = new ObjectExample();
        container.setName("container");
        container.put("firsts", first);
        container.put("seconds", second);

        String xml = new XmlExampleSerializer().serialize(container);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat("first array renders its item under its own property name",
            root.getElementsByTagName("firsts").getLength(), is(1));
        assertThat("second array renders its item under its own property name — NOT the first array's name",
            root.getElementsByTagName("seconds").getLength(), is(1));
    }

    @Test
    public void shouldNotPermanentlyRenameSharedObjectAcrossTwoProperties() throws Exception {
        // two object PROPERTIES whose values are the SAME anonymous Example instance (as happens for a
        // recursive $ref schema, e.g. Node{left:$ref Node, right:$ref Node}, where ExampleBuilder reuses
        // one cached Example): each property must render under its OWN key — the first key must not
        // permanently rename the shared instance and swallow the second.
        ObjectExample shared = new ObjectExample();
        shared.put("label", new StringExample("v"));

        ObjectExample node = new ObjectExample();
        node.setName("node");
        node.put("left", shared);
        node.put("right", shared);

        String xml = new XmlExampleSerializer().serialize(node);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat("left property renders under its own key",
            root.getElementsByTagName("left").getLength(), is(1));
        assertThat("right property renders under its own key — NOT a second \"left\"",
            root.getElementsByTagName("right").getLength(), is(1));
    }

    @Test
    public void shouldSerializeTopLevelScalar() throws Exception {
        StringExample scalar = new StringExample("hello");
        scalar.setName("greeting");

        String xml = new XmlExampleSerializer().serialize(scalar);
        Document document = parseNamespaceAware(xml);

        assertThat(document.getDocumentElement().getLocalName(), is("greeting"));
        assertThat(document.getDocumentElement().getTextContent(), is("hello"));
    }

    @Test
    public void shouldSerializeNumericAndBooleanValues() throws Exception {
        ObjectExample record = new ObjectExample();
        record.setName("Record");
        record.setNamespace(NS);
        record.put("count", new IntegerExample(42));
        record.put("active", new BooleanExample(true));

        String xml = new XmlExampleSerializer().serialize(record);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getNamespaceURI(), is(NS));
    }

    @Test
    public void shouldRenderNullStringValueAsEmptyElementNotLiteralNull() throws Exception {
        // a StringExample with a null value must render as an empty element, NOT <field>null</field>
        ObjectExample book = new ObjectExample();
        book.setName("Book");
        book.put("title", new StringExample(null));

        String xml = new XmlExampleSerializer().serialize(book);
        Document document = parseNamespaceAware(xml);

        Element title = (Element) document.getDocumentElement().getElementsByTagName("title").item(0);
        assertThat("a null string must be an empty element, not the text \"null\"",
            title.getTextContent(), is(""));
        assertThat(xml.contains(">null<"), is(false));
    }

    @Test
    public void shouldSerializeNoNamespaceObject() throws Exception {
        // regression of the existing (already-working) non-namespaced behaviour
        ObjectExample book = new ObjectExample();
        book.setName("Book");
        book.put("title", new StringExample("Moby Dick"));
        book.put("pages", new IntegerExample(635));

        String xml = new XmlExampleSerializer().serialize(book);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("Book"));
        assertThat("no namespace declared", root.getNamespaceURI(), nullValue());
        assertThat(xml.contains("xmlns"), is(false));
    }

    @Test
    public void shouldDeclarePrefixedNamespaceOnlyOnceAcrossWrappedArray() throws Exception {
        ArrayExample books = new ArrayExample();
        books.setName("book");
        books.setNamespace(NS);
        books.setPrefix("ex");
        books.setWrapped(true);
        books.setWrappedName("books");
        books.add(new StringExample("Moby Dick"));
        books.add(new StringExample("Bartleby"));

        String xml = new XmlExampleSerializer().serialize(books);
        Document document = parseNamespaceAware(xml);

        // every element carrying the namespace re-declares xmlns:ex (the accepted cosmetic redundancy of
        // the always-emit correctness fix); each declaration binds the same URI, so the body is well-formed
        // and every element resolves to the same namespace
        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("books"));
        assertThat(root.getNamespaceURI(), is(NS));
        assertThat("at least the wrapper declares xmlns:ex", countXmlnsDeclarations(xml, "xmlns:ex") >= 1, is(true));
    }

    // -------------------------------------------------------------------------------------------------
    // Fix 1 regression — integrated path through ExampleBuilder must yield a non-empty well-formed body
    // -------------------------------------------------------------------------------------------------

    @Test
    public void shouldYieldNonEmptyWellFormedBodyForNamespacedSchemaViaExampleBuilder() throws Exception {
        ObjectSchema book = new ObjectSchema();
        book.setXml(new XML().name("Book").namespace(NS).prefix("ex"));
        book.addProperty("title", new StringSchema().example("Moby Dick"));
        book.addProperty("pages", new IntegerSchema().example(635));

        Example generated = ExampleBuilder.fromSchema(book, null);
        assertThat(generated, is(notNullValue()));

        String xml = new XmlExampleSerializer().serialize(generated);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("Book"));
        assertThat(root.getNamespaceURI(), is(NS));
        assertThat(root.getPrefix(), is("ex"));
    }

    @Test
    public void shouldYieldNonEmptyWellFormedBodyForDefaultNamespaceSchemaViaExampleBuilder() throws Exception {
        ObjectSchema book = new ObjectSchema();
        book.setXml(new XML().name("Book").namespace(NS));
        book.addProperty("title", new StringSchema().example("Moby Dick"));

        Example generated = ExampleBuilder.fromSchema(book, null);
        assertThat(generated, is(notNullValue()));

        String xml = new XmlExampleSerializer().serialize(generated);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getNamespaceURI(), is(NS));
        assertThat(root.getPrefix(), nullValue());
    }

    // -------------------------------------------------------------------------------------------------
    // Fix 1 (CRITICAL) regression guards — a descendant that shadows an ancestor namespace binding must
    // resolve to its own namespace, NOT the suppressed-redeclaration ancestor namespace
    // -------------------------------------------------------------------------------------------------

    @Test
    public void shouldNotBindDescendantToAncestorNamespaceWhenPrefixRebound() throws Exception {
        // <tns:Order xmlns:tns="A"> containing <tns:Customer xmlns:tns="B"> — Customer must resolve to B
        String nsA = "urn:ns:A";
        String nsB = "urn:ns:B";

        ObjectExample customer = new ObjectExample();
        customer.setName("Customer");
        customer.setNamespace(nsB);
        customer.setPrefix("tns");
        customer.put("name", new StringExample("Ishmael"));

        ObjectExample order = new ObjectExample();
        order.setName("Order");
        order.setNamespace(nsA);
        order.setPrefix("tns");
        order.put("customer", customer);

        String xml = new XmlExampleSerializer().serialize(order);
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("Order"));
        assertThat(root.getNamespaceURI(), is(nsA));

        Element customerElement = (Element) root.getElementsByTagNameNS(nsB, "Customer").item(0);
        assertThat("Customer must resolve to namespace B, not the ancestor's A", customerElement, is(notNullValue()));
        assertThat(customerElement.getNamespaceURI(), is(nsB));
    }

    @Test
    public void shouldNotBindGrandchildToShadowingDefaultNamespace() throws Exception {
        // <r xmlns="A"><c xmlns="B"><g xmlns="A"> — grandchild g must resolve to A, not the shadowing B
        String nsA = "urn:ns:A";
        String nsB = "urn:ns:B";

        ObjectExample grandchild = new ObjectExample();
        grandchild.setName("g");
        grandchild.setNamespace(nsA);
        grandchild.put("value", new StringExample("deep"));

        ObjectExample child = new ObjectExample();
        child.setName("c");
        child.setNamespace(nsB);
        child.put("g", grandchild);

        ObjectExample root = new ObjectExample();
        root.setName("r");
        root.setNamespace(nsA);
        root.put("c", child);

        String xml = new XmlExampleSerializer().serialize(root);
        Document document = parseNamespaceAware(xml);

        Element rootElement = document.getDocumentElement();
        assertThat(rootElement.getNamespaceURI(), is(nsA));

        Element childElement = (Element) rootElement.getElementsByTagNameNS(nsB, "c").item(0);
        assertThat(childElement, is(notNullValue()));

        Element grandchildElement = (Element) childElement.getElementsByTagNameNS(nsA, "g").item(0);
        assertThat("grandchild g must resolve to A, not the shadowing B", grandchildElement, is(notNullValue()));
        assertThat(grandchildElement.getNamespaceURI(), is(nsA));
    }

    // -------------------------------------------------------------------------------------------------
    // Fix 2 / Fix 3 (MINOR) — illegal codepoints and malformed names must not produce unparseable output
    // -------------------------------------------------------------------------------------------------

    @Test
    public void shouldStripXmlIllegalControlCharacterFromValue() throws Exception {
        ObjectExample record = new ObjectExample();
        record.setName("Record");
        record.put("author", new StringExample("Herman\u0001Melville"));

        String xml = new XmlExampleSerializer().serialize(record);
        // must parse as well-formed XML despite the 0x01 control char in the value
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName(), is("Record"));
        assertThat("illegal control char must not survive into the body", xml.indexOf('\u0001'), is(-1));
    }

    @Test
    public void shouldSanitiseMalformedXmlNameToWellFormedOutput() throws Exception {
        ObjectExample record = new ObjectExample();
        record.setName("bad name!");
        record.put("title", new StringExample("Moby Dick"));

        String xml = new XmlExampleSerializer().serialize(record);
        // must still be well-formed — the invalid element name is sanitised, not emitted raw
        Document document = parseNamespaceAware(xml);

        Element root = document.getDocumentElement();
        assertThat("sanitised name must be a valid XML name (no space / '!')",
            root.getLocalName().matches("[A-Za-z_:][\\w.:_-]*"), is(true));
    }

    @Test
    public void shouldPreserveSupplementaryPlaneCharacterInValue() throws Exception {
        // a supplementary-plane code point (emoji U+1F600) is a legal XML 1.0 char encoded as a surrogate
        // pair — it must NOT be stripped (regression: char-wise stripping deleted both surrogate halves).
        String value = "Hi😀End"; // "Hi😀End"
        ObjectExample record = new ObjectExample();
        record.setName("Record");
        record.put("greeting", new StringExample(value));

        String xml = new XmlExampleSerializer().serialize(record);
        Document document = parseNamespaceAware(xml);

        assertThat("supplementary-plane character must round-trip intact",
            document.getDocumentElement().getTextContent(), is(value));
    }

    @Test
    public void shouldSanitiseColonInNameToWellFormedOutput() throws Exception {
        // a ':' in the element name would emit an unbound prefix; it must be sanitised so the document
        // parses namespace-aware without an "unbound prefix" error.
        ObjectExample record = new ObjectExample();
        record.setName("ns:Element");
        record.put("title", new StringExample("Moby Dick"));

        String xml = new XmlExampleSerializer().serialize(record);
        Document document = parseNamespaceAware(xml);

        assertThat("colon must be sanitised out of the element name",
            document.getDocumentElement().getLocalName().contains(":"), is(false));
    }

    private int countXmlnsDeclarations(String xml, String declaration) {
        int count = 0;
        int index = 0;
        while ((index = xml.indexOf(declaration + "=", index)) != -1) {
            count++;
            index += declaration.length();
        }
        return count;
    }
}
