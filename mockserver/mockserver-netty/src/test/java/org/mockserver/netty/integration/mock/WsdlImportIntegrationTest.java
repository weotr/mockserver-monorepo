package org.mockserver.netty.integration.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Integration test for the WSDL import endpoint (PUT /mockserver/wsdl).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Importing a WSDL 1.1 document with a SOAP 1.1 binding creates an expectation per operation.</li>
 *   <li>The generated expectation matches on SOAPAction header and returns a skeleton SOAP envelope.</li>
 *   <li>Sending a matching POST with the correct SOAPAction returns the skeleton response.</li>
 * </ul>
 */
public class WsdlImportIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static MockServerClient mockServerClient;
    private static int mockServerPort;

    @BeforeClass
    public static void startServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    // A minimal WSDL 1.1 with SOAP 1.1 binding, one service/port/operation
    private static final String WSDL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<definitions xmlns=\"http://schemas.xmlsoap.org/wsdl/\"\n" +
        "             xmlns:soap=\"http://schemas.xmlsoap.org/wsdl/soap/\"\n" +
        "             xmlns:tns=\"http://example.com/weather\"\n" +
        "             targetNamespace=\"http://example.com/weather\"\n" +
        "             name=\"WeatherService\">\n" +
        "  <message name=\"GetWeatherRequest\"/>\n" +
        "  <message name=\"GetWeatherResponse\"/>\n" +
        "  <portType name=\"WeatherPortType\">\n" +
        "    <operation name=\"GetWeather\">\n" +
        "      <input message=\"tns:GetWeatherRequest\"/>\n" +
        "      <output message=\"tns:GetWeatherResponse\"/>\n" +
        "    </operation>\n" +
        "  </portType>\n" +
        "  <binding name=\"WeatherBinding\" type=\"tns:WeatherPortType\">\n" +
        "    <soap:binding style=\"document\" transport=\"http://schemas.xmlsoap.org/soap/http\"/>\n" +
        "    <operation name=\"GetWeather\">\n" +
        "      <soap:operation soapAction=\"http://example.com/GetWeather\"/>\n" +
        "      <input><soap:body use=\"literal\"/></input>\n" +
        "      <output><soap:body use=\"literal\"/></output>\n" +
        "    </operation>\n" +
        "  </binding>\n" +
        "  <service name=\"WeatherService\">\n" +
        "    <port name=\"WeatherPort\" binding=\"tns:WeatherBinding\">\n" +
        "      <soap:address location=\"http://localhost/ws/weather\"/>\n" +
        "    </port>\n" +
        "  </service>\n" +
        "</definitions>";

    @Test
    public void shouldImportWsdlAndCreateExpectation() throws Exception {
        // Import the WSDL
        String response = sendPutRequest("/mockserver/wsdl", WSDL, "application/xml");
        assertThat("PUT /mockserver/wsdl should return 201", response, containsString("201"));

        // Parse the response body — should contain the created expectation(s)
        String body = extractJsonBody(response);
        JsonNode expectations = OBJECT_MAPPER.readTree(body);
        assertThat("should create at least one expectation", expectations.size(), is(greaterThanOrEqualTo(1)));

        // Verify the expectation matches on POST with SOAPAction header
        JsonNode first = expectations.get(0);
        assertThat(first.path("httpRequest").path("method").asText(), is("POST"));
        assertThat(first.path("httpRequest").path("path").asText(), is("/ws/weather"));

        // Verify response contains a SOAP envelope with GetWeatherResponse
        String responseBody = first.path("httpResponse").path("body").asText();
        assertThat(responseBody, containsString("GetWeatherResponse"));
        assertThat(responseBody, containsString("soapenv:Envelope"));
    }

    @Test
    public void shouldMatchSoapRequestAndReturnSkeletonResponse() throws Exception {
        // Import the WSDL first
        String importResponse = sendPutRequest("/mockserver/wsdl", WSDL, "application/xml");
        assertThat(importResponse, containsString("201"));

        // Send a matching SOAP POST request with SOAPAction header
        String soapRequest = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\">\n" +
            "  <soapenv:Body>\n" +
            "    <GetWeather xmlns=\"http://example.com/weather\"/>\n" +
            "  </soapenv:Body>\n" +
            "</soapenv:Envelope>";

        String response = sendPostWithSoapAction(
            "/ws/weather",
            soapRequest,
            "\"http://example.com/GetWeather\""
        );

        assertThat("matching SOAP request should return 200", response, containsString("200"));
        String body = extractBody(response);
        assertThat("response should contain SOAP envelope", body, containsString("soapenv:Envelope"));
        assertThat("response should contain GetWeatherResponse", body, containsString("GetWeatherResponse"));
        assertThat("response should contain target namespace", body, containsString("http://example.com/weather"));
    }

    // ---- HTTP helpers ----

    private String sendPutRequest(String path, String body, String contentType) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String headers = "PUT " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + mockServerPort + "\r\n" +
                "Connection: close\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "\r\n";
            output.write(headers.getBytes(StandardCharsets.UTF_8));
            output.write(bodyBytes);
            output.flush();
            return IOUtils.toString(socket.getInputStream(), StandardCharsets.UTF_8);
        }
    }

    private String sendPostWithSoapAction(String path, String body, String soapAction) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String headers = "POST " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + mockServerPort + "\r\n" +
                "Connection: close\r\n" +
                "Content-Type: text/xml; charset=utf-8\r\n" +
                "SOAPAction: " + soapAction + "\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "\r\n";
            output.write(headers.getBytes(StandardCharsets.UTF_8));
            output.write(bodyBytes);
            output.flush();
            return IOUtils.toString(socket.getInputStream(), StandardCharsets.UTF_8);
        }
    }

    private String extractJsonBody(String httpResponse) {
        int bodyStart = httpResponse.indexOf("\r\n\r\n");
        if (bodyStart < 0) {
            bodyStart = httpResponse.indexOf("\n\n");
            if (bodyStart < 0) {
                return httpResponse;
            }
            return httpResponse.substring(bodyStart + 2);
        }
        return httpResponse.substring(bodyStart + 4);
    }

    private String extractBody(String httpResponse) {
        // For chunked encoding, extract the body after the blank line
        return extractJsonBody(httpResponse);
    }
}
