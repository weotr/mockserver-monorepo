package org.mockserver.saml;

import org.junit.Test;
import org.mockserver.keys.AsymmetricKeyPairAlgorithm;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertTrue;

/**
 * Behavioural unit tests for {@link SamlResponseBuilder} exercised as the pure function it is —
 * a {@link SamlAssertionStore.Provider} in, signed {@code <Response>} XML out — without the
 * {@link SamlSsoCallback}, the auto-POST HTML form, or the singleton store. This complements
 * {@link SamlProviderGeneratorTest} (which drives the builder via the SSO callback) by covering the
 * builder contract directly, most importantly the {@code InResponseTo} embedding and the
 * security-relevant negatives (tampered signature, expired window, wrong audience).
 */
public class SamlResponseBuilderTest {

    private static final String NS_ASSERTION = "urn:oasis:names:tc:SAML:2.0:assertion";

    private final SamlResponseBuilder builder = new SamlResponseBuilder();

    // --- helpers -----------------------------------------------------------------------------

    private SamlAssertionStore.Provider provider(boolean expired, boolean wrongAudience, boolean tampered) {
        SamlProviderConfiguration config = new SamlProviderConfiguration();
        SamlSigningCredential credential = SamlSigningCredential.from(config);
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("role", "admin");
        return new SamlAssertionStore.Provider(
            "/saml/sso", null, null,
            "https://idp.test/idp", "https://sp.test/metadata",
            "https://sp.test/acs", "alice@example.com",
            "urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress",
            attributes, 3600,
            credential.getPrivateKey(), credential.getCertificate(), credential.getAlgorithm(),
            expired, wrongAudience, tampered);
    }

    private PublicKey publicKeyOf(SamlAssertionStore.Provider provider) {
        return provider.signingCertificate.getPublicKey();
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static boolean signatureValidates(Document doc, PublicKey publicKey) throws Exception {
        Element assertion = (Element) doc.getElementsByTagNameNS(NS_ASSERTION, "Assertion").item(0);
        assertion.setIdAttribute("ID", true);
        NodeList signatures = doc.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        if (signatures.getLength() != 1) {
            return false;
        }
        DOMValidateContext valContext = new DOMValidateContext(publicKey, signatures.item(0));
        XMLSignature signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(valContext);
        return signature.validate(valContext);
    }

    // --- positive: a default provider yields a valid, signed response ------------------------

    @Test
    public void buildsResponseWhoseAssertionSignatureValidatesAgainstTheSigningCertificate() throws Exception {
        SamlAssertionStore.Provider provider = provider(false, false, false);

        Document doc = parse(builder.buildSignedResponse(provider, null));

        assertThat(doc.getDocumentElement().getLocalName(), is("Response"));
        assertTrue("the enveloped assertion signature must validate against the signing certificate",
            signatureValidates(doc, publicKeyOf(provider)));
    }

    @Test
    public void embedsSubjectNameIdAndAudienceAndAttributes() throws Exception {
        SamlAssertionStore.Provider provider = provider(false, false, false);

        Document doc = parse(builder.buildSignedResponse(provider, null));

        assertThat(doc.getElementsByTagNameNS(NS_ASSERTION, "NameID").item(0).getTextContent(),
            is("alice@example.com"));
        assertThat(doc.getElementsByTagNameNS(NS_ASSERTION, "Audience").item(0).getTextContent(),
            is("https://sp.test/metadata"));
        assertThat(doc.getElementsByTagNameNS(NS_ASSERTION, "Attribute").getLength(), is(1));
    }

    // --- InResponseTo embedding (the contract reached only directly) -------------------------

    @Test
    public void inResponseToIsEmbeddedIntoResponseAndSubjectConfirmationData() throws Exception {
        SamlAssertionStore.Provider provider = provider(false, false, false);

        Document doc = parse(builder.buildSignedResponse(provider, "_authnreq-42"));

        assertThat("Response@InResponseTo must echo the AuthnRequest ID",
            doc.getDocumentElement().getAttribute("InResponseTo"), is("_authnreq-42"));
        Element scd = (Element) doc.getElementsByTagNameNS(NS_ASSERTION, "SubjectConfirmationData").item(0);
        assertThat("SubjectConfirmationData@InResponseTo must echo the AuthnRequest ID",
            scd.getAttribute("InResponseTo"), is("_authnreq-42"));
    }

    @Test
    public void nullInResponseToOmitsTheAttribute() throws Exception {
        SamlAssertionStore.Provider provider = provider(false, false, false);

        Document doc = parse(builder.buildSignedResponse(provider, null));

        assertThat("no InResponseTo must be emitted for an IdP-initiated (null) request",
            doc.getDocumentElement().getAttribute("InResponseTo"), is(""));
    }

    @Test
    public void embeddingInResponseToDoesNotBreakTheSignature() throws Exception {
        // InResponseTo is set BEFORE signing, so the digest must still cover it and validate.
        SamlAssertionStore.Provider provider = provider(false, false, false);

        Document doc = parse(builder.buildSignedResponse(provider, "_authnreq-99"));

        assertTrue("a response carrying InResponseTo must still verify",
            signatureValidates(doc, publicKeyOf(provider)));
    }

    // --- security-relevant negatives --------------------------------------------------------

    @Test
    public void tamperedSignatureFlagProducesAResponseThatFailsValidation() throws Exception {
        SamlAssertionStore.Provider provider = provider(false, false, true);

        Document doc = parse(builder.buildSignedResponse(provider, null));

        assertThat("tamperedSignature must yield a signature that does not validate",
            signatureValidates(doc, publicKeyOf(provider)), is(false));
    }

    @Test
    public void expiredAssertionFlagPutsValidityWindowInThePast() throws Exception {
        SamlAssertionStore.Provider provider = provider(true, false, false);

        Document doc = parse(builder.buildSignedResponse(provider, null));

        Element conditions = (Element) doc.getElementsByTagNameNS(NS_ASSERTION, "Conditions").item(0);
        assertTrue("expiredAssertion must place Conditions/NotOnOrAfter in the past",
            Instant.parse(conditions.getAttribute("NotOnOrAfter")).isBefore(Instant.now()));
        // the assertion is still correctly signed — expiry is a content/time defect, not a crypto defect
        assertTrue("an expired-but-signed assertion must still verify cryptographically",
            signatureValidates(doc, publicKeyOf(provider)));
    }

    @Test
    public void wrongAudienceFlagEmitsAnAudienceThatDoesNotMatchTheSpEntityId() throws Exception {
        SamlAssertionStore.Provider provider = provider(false, true, false);

        Document doc = parse(builder.buildSignedResponse(provider, null));

        assertThat("wrongAudience must NOT equal the SP entityId",
            doc.getElementsByTagNameNS(NS_ASSERTION, "Audience").item(0).getTextContent(),
            is(not("https://sp.test/metadata")));
        assertTrue("wrongAudience is a content defect; the signature must still verify",
            signatureValidates(doc, publicKeyOf(provider)));
    }

    @Test
    public void defaultProviderProducesNonExpiredFutureValidityWindow() throws Exception {
        SamlAssertionStore.Provider provider = provider(false, false, false);

        Document doc = parse(builder.buildSignedResponse(provider, null));

        Element conditions = (Element) doc.getElementsByTagNameNS(NS_ASSERTION, "Conditions").item(0);
        assertTrue("a non-expired assertion must have NotOnOrAfter in the future",
            Instant.parse(conditions.getAttribute("NotOnOrAfter")).isAfter(Instant.now()));
    }

    // --- the signing algorithm flows through to the XML-DSig method --------------------------

    @Test
    public void ecdsaCredentialProducesAResponseThatVerifies() throws Exception {
        SamlProviderConfiguration config = new SamlProviderConfiguration().setSigningAlgorithm("ES256");
        SamlSigningCredential credential = SamlSigningCredential.from(config);
        SamlAssertionStore.Provider provider = new SamlAssertionStore.Provider(
            "/saml/sso", null, null, "https://idp.test/idp", "https://sp.test/metadata",
            "https://sp.test/acs", "bob@example.com", null,
            new LinkedHashMap<>(), 3600,
            credential.getPrivateKey(), credential.getCertificate(), credential.getAlgorithm(),
            false, false, false);

        assertThat(credential.getAlgorithm(), is(AsymmetricKeyPairAlgorithm.EC256_SHA256));
        Document doc = parse(builder.buildSignedResponse(provider, null));
        assertTrue("an ES256-signed assertion must validate against its EC certificate",
            signatureValidates(doc, publicKeyOf(provider)));
    }

    // --- thread-safety of the shared factories ----------------------------------------------

    @Test
    public void concurrentBuildsEachProduceAValidSignedAssertion() throws Exception {
        // SamlResponseBuilder shares the DocumentBuilderFactory / TransformerFactory /
        // XMLSignatureFactory as static singletons but must create a fresh DocumentBuilder /
        // Transformer per call. If a non-thread-safe instance leaked across threads, parsing or
        // signing would corrupt the digest and one or more concurrent responses would fail to
        // validate (or the build would throw). Drive many concurrent builds — each with a distinct
        // InResponseTo so the signed content differs — and require every result to verify.
        SamlAssertionStore.Provider provider = provider(false, false, false);
        PublicKey publicKey = publicKeyOf(provider);
        int threads = 8;
        int buildsPerThread = 25;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                tasks.add(() -> {
                    for (int i = 0; i < buildsPerThread; i++) {
                        String inResponseTo = "_req-" + threadId + "-" + i;
                        Document doc = parse(builder.buildSignedResponse(provider, inResponseTo));
                        if (!inResponseTo.equals(doc.getDocumentElement().getAttribute("InResponseTo"))) {
                            return false;
                        }
                        if (!signatureValidates(doc, publicKey)) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            List<Future<Boolean>> results = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);
            for (Future<Boolean> result : results) {
                assertTrue("every concurrently-built assertion must verify and carry its own InResponseTo",
                    result.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
