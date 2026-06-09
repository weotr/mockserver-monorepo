package org.mockserver.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link WebhookServer}.
 * <p>
 * Starts a real HTTPS server on an ephemeral port with a self-signed certificate,
 * sends real HTTP requests, and asserts the responses.
 */
class WebhookServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static WebhookServer webhookServer;
    private static SSLContext clientSslContext;
    private static int port;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void startServer() throws Exception {
        // Generate self-signed certificate for testing using keytool
        Path ksPath = tempDir.resolve("test-keystore.p12");
        char[] password = "changeit".toCharArray();

        ProcessBuilder keytool = new ProcessBuilder(
            "keytool", "-genkeypair",
            "-alias", "server",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "1",
            "-dname", "CN=localhost",
            "-storetype", "PKCS12",
            "-keystore", ksPath.toString(),
            "-storepass", new String(password),
            "-keypass", new String(password)
        );
        keytool.inheritIO();
        Process proc = keytool.start();
        int exitCode = proc.waitFor();
        assertEquals(0, exitCode, "keytool should succeed");

        // Load the generated keystore
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var is = java.nio.file.Files.newInputStream(ksPath)) {
            ks.load(is, password);
        }

        // Server SSLContext
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);

        SSLContext serverSslContext = SSLContext.getInstance("TLS");
        serverSslContext.init(kmf.getKeyManagers(), null, null);

        // Client SSLContext (trusts the self-signed cert)
        Certificate cert = ks.getCertificate("server");
        KeyStore trustKs = KeyStore.getInstance("PKCS12");
        trustKs.load(null, null);
        trustKs.setCertificateEntry("server", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustKs);

        clientSslContext = SSLContext.getInstance("TLS");
        clientSslContext.init(null, tmf.getTrustManagers(), null);

        // Start server on ephemeral port
        SidecarInjectionConfig config = new SidecarInjectionConfig();
        webhookServer = new WebhookServer(
            new InetSocketAddress("127.0.0.1", 0), serverSslContext, config
        );
        webhookServer.start();
        port = webhookServer.getPort();
    }

    @AfterAll
    static void stopServer() {
        if (webhookServer != null) {
            webhookServer.stop(0);
        }
    }

    @Nested
    class HealthEndpoint {

        @Test
        void healthzReturns200() throws Exception {
            HttpsURLConnection conn = createConnection("/healthz", "GET");
            try {
                assertThat(conn.getResponseCode(), is(200));
                String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(body, is("ok"));
            } finally {
                conn.disconnect();
            }
        }
    }

    @Nested
    class InjectEndpoint {

        @Test
        void optedInPodGetsInjected() throws Exception {
            String reviewJson = buildAdmissionReview("test-uid-inject", true, false);
            HttpsURLConnection conn = createConnection("/inject", "POST");
            try {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(reviewJson.getBytes(StandardCharsets.UTF_8));
                }

                assertThat(conn.getResponseCode(), is(200));

                byte[] responseBytes = conn.getInputStream().readAllBytes();
                JsonNode response = MAPPER.readTree(responseBytes);

                assertThat(response.path("apiVersion").asText(), is("admission.k8s.io/v1"));
                assertThat(response.path("kind").asText(), is("AdmissionReview"));

                JsonNode admissionResponse = response.path("response");
                assertThat(admissionResponse.path("uid").asText(), is("test-uid-inject"));
                assertTrue(admissionResponse.path("allowed").asBoolean());
                assertTrue(admissionResponse.has("patch"), "should have patch");
                assertThat(admissionResponse.path("patchType").asText(), is("JSONPatch"));

                // Decode and verify patch
                String patchBase64 = admissionResponse.get("patch").asText();
                byte[] patchBytes = Base64.getDecoder().decode(patchBase64);
                JsonNode patch = MAPPER.readTree(patchBytes);
                assertTrue(patch.isArray());
                assertTrue(patch.size() > 0);
            } finally {
                conn.disconnect();
            }
        }

        @Test
        void nonOptedPodIsAllowed() throws Exception {
            String reviewJson = buildAdmissionReview("test-uid-noopt", false, false);
            HttpsURLConnection conn = createConnection("/inject", "POST");
            try {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(reviewJson.getBytes(StandardCharsets.UTF_8));
                }

                assertThat(conn.getResponseCode(), is(200));

                JsonNode response = MAPPER.readTree(conn.getInputStream().readAllBytes());
                JsonNode admissionResponse = response.path("response");
                assertTrue(admissionResponse.path("allowed").asBoolean());
                assertFalse(admissionResponse.has("patch"));
            } finally {
                conn.disconnect();
            }
        }

        @Test
        void emptyBodyReturnsAllowWithMessage() throws Exception {
            HttpsURLConnection conn = createConnection("/inject", "POST");
            try {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(new byte[0]);
                }

                assertThat(conn.getResponseCode(), is(200));

                JsonNode response = MAPPER.readTree(conn.getInputStream().readAllBytes());
                JsonNode admissionResponse = response.path("response");
                assertTrue(admissionResponse.path("allowed").asBoolean());
                assertThat(admissionResponse.path("status").path("message").asText(),
                    containsString("empty request body"));
            } finally {
                conn.disconnect();
            }
        }

        @Test
        void malformedJsonReturnsAllowWithError() throws Exception {
            HttpsURLConnection conn = createConnection("/inject", "POST");
            try {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write("{not valid json".getBytes(StandardCharsets.UTF_8));
                }

                assertThat(conn.getResponseCode(), is(200));

                JsonNode response = MAPPER.readTree(conn.getInputStream().readAllBytes());
                JsonNode admissionResponse = response.path("response");
                assertTrue(admissionResponse.path("allowed").asBoolean(),
                    "malformed body should still allow pod (fail-open)");
                assertTrue(admissionResponse.path("status").has("message"));
            } finally {
                conn.disconnect();
            }
        }

        @Test
        void getMethodReturns405() throws Exception {
            HttpsURLConnection conn = createConnection("/inject", "GET");
            try {
                assertThat(conn.getResponseCode(), is(405));
            } finally {
                conn.disconnect();
            }
        }
    }

    @Nested
    class ConfigFromEnv {

        @Test
        void defaultConfigValues() {
            // With no env vars set, should use defaults from SidecarInjectionConfig
            SidecarInjectionConfig config = new SidecarInjectionConfig();
            assertThat(config.getServerPort(), is(1080));
            assertThat(config.getRedirectPorts(), is("80,443"));
            assertThat(config.getRunAsUser(), is(65534));
            assertThat(config.getLogLevel(), is("INFO"));
        }
    }

    @Nested
    class PrivateKeyLoading {

        @Test
        void pkcs1FormatIsRejectedWithClearMessage() {
            String pkcs1Pem = "-----BEGIN RSA PRIVATE KEY-----\n"
                + "MIIBogIBAAJBALRiMLAR/fakekeydata\n"
                + "-----END RSA PRIVATE KEY-----\n";

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> WebhookServer.loadPkcs8PrivateKey(pkcs1Pem));
            assertThat(ex.getMessage(), containsString("PKCS#1"));
            assertThat(ex.getMessage(), containsString("PKCS#8"));
            assertThat(ex.getMessage(), containsString("openssl genpkey"));
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private HttpsURLConnection createConnection(String path, String method) throws Exception {
        URL url = URI.create("https://127.0.0.1:" + port + path).toURL();
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(clientSslContext.getSocketFactory());
        // TEST-ONLY: accept any hostname for this in-process test client. It connects to the
        // webhook test server on 127.0.0.1 using an ephemeral self-signed certificate, where
        // hostname verification is not meaningful. This is not production code — the real
        // webhook serves TLS normally and the kube-apiserver verifies it via the configured
        // caBundle. The corresponding CodeQL alert (java/unsafe-hostname-verification) is
        // dismissed as "used in tests".
        conn.setHostnameVerifier((hostname, session) -> true);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        return conn;
    }

    private static String buildAdmissionReview(String uid, boolean optIn, boolean alreadyInjected) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("  \"apiVersion\": \"admission.k8s.io/v1\",");
        sb.append("  \"kind\": \"AdmissionReview\",");
        sb.append("  \"request\": {");
        sb.append("    \"uid\": \"").append(uid).append("\",");
        sb.append("    \"kind\": { \"group\": \"\", \"version\": \"v1\", \"kind\": \"Pod\" },");
        sb.append("    \"resource\": { \"group\": \"\", \"version\": \"v1\", \"resource\": \"pods\" },");
        sb.append("    \"operation\": \"CREATE\",");
        sb.append("    \"object\": {");
        sb.append("      \"apiVersion\": \"v1\",");
        sb.append("      \"kind\": \"Pod\",");
        sb.append("      \"metadata\": {");
        sb.append("        \"name\": \"test-pod\",");
        sb.append("        \"namespace\": \"default\"");
        if (optIn || alreadyInjected) {
            sb.append(",        \"annotations\": {");
            sb.append("          \"mockserver.org/inject\": \"true\"");
            if (alreadyInjected) {
                sb.append(",          \"mockserver.org/injected\": \"true\"");
            }
            sb.append("        }");
        }
        sb.append("      },");
        sb.append("      \"spec\": {");
        sb.append("        \"containers\": [");
        sb.append("          { \"name\": \"app\", \"image\": \"myapp:latest\" }");
        sb.append("        ]");
        sb.append("      }");
        sb.append("    }");
        sb.append("  }");
        sb.append("}");
        return sb.toString();
    }

}
