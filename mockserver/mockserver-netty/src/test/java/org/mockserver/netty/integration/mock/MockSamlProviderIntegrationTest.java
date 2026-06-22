package org.mockserver.netty.integration.mock;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.mock.Expectation;
import org.mockserver.saml.SamlProviderConfiguration;
import org.mockserver.saml.SamlSigningCredential;
import org.mockserver.socket.tls.PEMToFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end integration test for the mock SAML IdP standing up through a real Netty server via the
 * typed {@link org.mockserver.client.MockServerClient#mockSamlProvider} client API.
 *
 * <p>Exercises the full SP-initiated Web-Browser-SSO POST profile over real HTTP: the client mocks
 * the provider, the metadata endpoint is fetched, an AuthnRequest is POSTed to the SSO endpoint, the
 * returned auto-POST form is parsed, and the embedded SAML Response signature is verified against the
 * certificate published in the metadata — proving a fresh signed Response per request, ACS form-POST,
 * and RelayState echo all work end-to-end.
 */
public class MockSamlProviderIntegrationTest {

    private static ClientAndServer server;
    private static int port;
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static final String ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String DS_NS = "http://www.w3.org/2000/09/xmldsig#";

    @BeforeClass
    public static void startServer() {
        server = startClientAndServer();
        port = server.getPort();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(server);
    }

    @Before
    public void resetExpectations() {
        server.reset();
    }

    @Test
    public void mockSamlProviderRoundTripsAndReturnsExpectations() {
        Expectation[] expectations = server.mockSamlProvider(
            new SamlProviderConfiguration()
                .setIdpEntityId("http://localhost:" + port + "/saml/idp")
                .setSpEntityId("https://sp.example.com/metadata")
                .setAssertionConsumerServiceUrl("https://sp.example.com/acs")
                .setSubjectNameId("integration@example.com")
        );

        assertThat(expectations, is(notNullValue()));
        assertThat(expectations.length, is(3));
        assertThat(expectations[0].getId(), is("saml.metadata"));
        assertThat(expectations[1].getId(), is("saml.sso"));
        assertThat(expectations[2].getId(), is("saml.slo"));
    }

    @Test
    public void fullSsoFlowProducesSignedResponseVerifiableAgainstMetadataCert() throws Exception {
        server.mockSamlProvider(
            new SamlProviderConfiguration()
                .setIdpEntityId("http://localhost:" + port + "/saml/idp")
                .setSpEntityId("https://sp.example.com/metadata")
                .setAssertionConsumerServiceUrl("https://sp.example.com/acs")
                .setSubjectNameId("integration@example.com")
        );

        // 1. GET the metadata and extract the IdP signing certificate
        HttpResponse<String> metadata = HTTP.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/saml/metadata")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(metadata.statusCode(), is(200));
        assertThat(metadata.headers().firstValue("content-type").orElse(""), containsString("samlmetadata+xml"));

        Document metadataDoc = parse(metadata.body());
        String certB64 = metadataDoc.getElementsByTagNameNS(DS_NS, "X509Certificate").item(0).getTextContent().trim();
        PublicKey idpPublicKey = ((X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(certB64)))).getPublicKey();

        // 2. POST an AuthnRequest (form-urlencoded SAMLRequest + RelayState) to the SSO endpoint
        String authnRequest = "<samlp:AuthnRequest xmlns:samlp=\"urn:oasis:names:tc:SAML:2.0:protocol\""
            + " ID=\"_authn-integration\" Version=\"2.0\"/>";
        String samlRequestB64 = Base64.getEncoder().encodeToString(authnRequest.getBytes(StandardCharsets.UTF_8));
        String formBody = "SAMLRequest=" + urlEncode(samlRequestB64) + "&RelayState=" + urlEncode("/back/to/app");

        HttpResponse<String> sso = HTTP.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/saml/sso"))
                .header("content-type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());

        assertThat(sso.statusCode(), is(200));
        String html = sso.body();
        // auto-POST form back to the SP ACS, echoing RelayState
        assertThat(html, containsString("action=\"https://sp.example.com/acs\""));
        assertThat(html, containsString("name=\"RelayState\""));
        assertThat(html, containsString("/back/to/app"));

        // 3. Parse the SAMLResponse out of the form and verify its assertion signature
        String responseXml = new String(
            Base64.getDecoder().decode(extractHiddenInput(html, "SAMLResponse")), StandardCharsets.UTF_8);
        Document doc = parse(responseXml);
        assertThat(doc.getDocumentElement().getLocalName(), is("Response"));
        assertThat(doc.getDocumentElement().getAttribute("InResponseTo"), is("_authn-integration"));

        NodeList nameIds = doc.getElementsByTagNameNS(ASSERTION_NS, "NameID");
        assertThat(nameIds.item(0).getTextContent(), is("integration@example.com"));

        Element assertion = (Element) doc.getElementsByTagNameNS(ASSERTION_NS, "Assertion").item(0);
        assertion.setIdAttribute("ID", true);
        NodeList signatures = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        assertThat("expected a ds:Signature on the assertion", signatures.getLength(), is(1));
        DOMValidateContext valContext = new DOMValidateContext(idpPublicKey, signatures.item(0));
        XMLSignature signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(valContext);
        assertTrue("SAML assertion signature must validate against the metadata certificate",
            signature.validate(valContext));
    }

    @Test
    public void suppliedSigningCredentialViaClientIsPublishedInMetadata() throws Exception {
        // A user-supplied PEM signing credential (cert + private key) handed to the typed client must
        // actually reach the server: signingPrivateKeyPem is WRITE_ONLY (so the server never serializes
        // the key back out), which previously caused the client to silently DROP it from the outbound
        // PUT — the server then fell back to a self-signed cert and the published metadata cert was NOT
        // the user's. This proves the client now carries the key and the supplied cert is published.

        // generate a credential, export both parts to PEM
        SamlSigningCredential supplied = SamlSigningCredential.from(new SamlProviderConfiguration());
        String certPem = PEMToFile.certToPEM(supplied.getCertificate());
        String keyPem = "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(supplied.getPrivateKey().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";

        server.mockSamlProvider(
            new SamlProviderConfiguration()
                .setIdpEntityId("http://localhost:" + port + "/saml/idp")
                .setSigningCertificatePem(certPem)
                .setSigningPrivateKeyPem(keyPem)
        );

        HttpResponse<String> metadata = HTTP.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/saml/metadata")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(metadata.statusCode(), is(200));

        Document metadataDoc = parse(metadata.body());
        String publishedCertB64 = metadataDoc.getElementsByTagNameNS(DS_NS, "X509Certificate")
            .item(0).getTextContent().trim();

        assertThat("the published metadata cert must be the SUPPLIED cert, not a self-signed fallback",
            publishedCertB64, is(supplied.getCertificateBase64()));
    }

    @Test
    public void freshResponseIsMintedPerRequest() throws Exception {
        server.mockSamlProvider();

        String first = ssoResponseId();
        String second = ssoResponseId();
        assertThat("each SSO request must mint a fresh Response (distinct IDs)", first, is(notNullValue()));
        assertTrue("Response IDs must differ between requests", !first.equals(second));
    }

    private String ssoResponseId() throws Exception {
        HttpResponse<String> sso = HTTP.send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/saml/sso")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        String responseXml = new String(
            Base64.getDecoder().decode(extractHiddenInput(sso.body(), "SAMLResponse")), StandardCharsets.UTF_8);
        return parse(responseXml).getDocumentElement().getAttribute("ID");
    }

    // --- helpers ---

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static final Pattern HIDDEN_INPUT = Pattern.compile(
        "<input type=\"hidden\" name=\"([^\"]+)\" value=\"([^\"]*)\"");

    private static String extractHiddenInput(String html, String name) {
        Matcher matcher = HIDDEN_INPUT.matcher(html);
        while (matcher.find()) {
            if (matcher.group(1).equals(name)) {
                return matcher.group(2)
                    .replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">");
            }
        }
        return null;
    }
}
