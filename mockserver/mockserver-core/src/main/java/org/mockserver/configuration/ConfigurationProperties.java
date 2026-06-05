package org.mockserver.configuration;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.net.InetAddresses;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.mockserver.file.FileReader;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.memory.MemoryMonitoring;
import org.mockserver.memory.Summary;
import org.mockserver.model.ProxyPassMapping;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;
import org.mockserver.socket.tls.KeyAndCertificateFactory;
import org.slf4j.event.Level;

import java.io.*;
import java.lang.management.MemoryType;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;
import static org.mockserver.logging.MockServerLogger.configureLogger;
import static org.slf4j.event.Level.DEBUG;

/**
 * @author jamesdbloom
 */
public class ConfigurationProperties {

    private static final class LoggerHolder {
        private static final MockServerLogger LOGGER = new MockServerLogger(ConfigurationProperties.class);
    }

    private static final String DEFAULT_LOG_LEVEL = "INFO";

    // logging
    private static final String MOCKSERVER_LOG_LEVEL = "mockserver.logLevel";
    private static final String MOCKSERVER_DISABLE_SYSTEM_OUT = "mockserver.disableSystemOut";
    private static final String MOCKSERVER_DISABLE_LOGGING = "mockserver.disableLogging";
    private static final String MOCKSERVER_DETAILED_MATCH_FAILURES = "mockserver.detailedMatchFailures";
    private static final String MOCKSERVER_LAUNCH_UI_FOR_LOG_LEVEL_DEBUG = "mockserver.launchUIForLogLevelDebug";
    private static final String MOCKSERVER_METRICS_ENABLED = "mockserver.metricsEnabled";
    private static final String MOCKSERVER_SLOW_REQUEST_THRESHOLD_MILLIS = "mockserver.slowRequestThresholdMillis";
    private static final String MOCKSERVER_METRICS_REQUEST_DURATION_ROUTE_LABELS = "mockserver.metricsRequestDurationRouteLabels";
    private static final String MOCKSERVER_MCP_ENABLED = "mockserver.mcpEnabled";
    private static final String MOCKSERVER_LOG_LEVEL_OVERRIDES = "mockserver.logLevelOverrides";
    private static final String MOCKSERVER_COMPACT_LOG_FORMAT = "mockserver.compactLogFormat";

    // memory usage
    private static final String MOCKSERVER_MAX_EXPECTATIONS = "mockserver.maxExpectations";
    private static final String MOCKSERVER_MAX_LOG_ENTRIES = "mockserver.maxLogEntries";
    private static final String MOCKSERVER_MAX_WEB_SOCKET_EXPECTATIONS = "mockserver.maxWebSocketExpectations";
    private static final String MOCKSERVER_OUTPUT_MEMORY_USAGE_CSV = "mockserver.outputMemoryUsageCsv";
    private static final String MOCKSERVER_MEMORY_USAGE_CSV_DIRECTORY = "mockserver.memoryUsageCsvDirectory";

    // scalability
    private static final String MOCKSERVER_USE_NATIVE_TRANSPORT = "mockserver.useNativeTransport";
    private static final String MOCKSERVER_NIO_EVENT_LOOP_THREAD_COUNT = "mockserver.nioEventLoopThreadCount";
    private static final String MOCKSERVER_ACTION_HANDLER_THREAD_COUNT = "mockserver.actionHandlerThreadCount";
    private static final String MOCKSERVER_CLIENT_NIO_EVENT_LOOP_THREAD_COUNT = "mockserver.clientNioEventLoopThreadCount";
    private static final String MOCKSERVER_WEB_SOCKET_CLIENT_EVENT_LOOP_THREAD_COUNT = "mockserver.webSocketClientEventLoopThreadCount";
    private static final String MOCKSERVER_MAX_FUTURE_TIMEOUT = "mockserver.maxFutureTimeout";
    private static final String MOCKSERVER_MATCHERS_FAIL_FAST = "mockserver.matchersFailFast";

    // socket
    private static final String MOCKSERVER_MAX_SOCKET_TIMEOUT = "mockserver.maxSocketTimeout";
    private static final String MOCKSERVER_SOCKET_CONNECTION_TIMEOUT = "mockserver.socketConnectionTimeout";
    private static final String MOCKSERVER_CONNECTION_DELAY_MILLIS = "mockserver.connectionDelayMillis";
    private static final String MOCKSERVER_ALWAYS_CLOSE_SOCKET_CONNECTIONS = "mockserver.alwaysCloseSocketConnections";
    private static final String MOCKSERVER_LOCAL_BOUND_IP = "mockserver.localBoundIP";

    // http request parsing
    private static final String MOCKSERVER_MAX_INITIAL_LINE_LENGTH = "mockserver.maxInitialLineLength";
    private static final String MOCKSERVER_MAX_HEADER_SIZE = "mockserver.maxHeaderSize";
    private static final String MOCKSERVER_MAX_CHUNK_SIZE = "mockserver.maxChunkSize";
    private static final String MOCKSERVER_MAX_REQUEST_BODY_SIZE = "mockserver.maxRequestBodySize";
    private static final String MOCKSERVER_MAX_RESPONSE_BODY_SIZE = "mockserver.maxResponseBodySize";
    private static final String MOCKSERVER_MAX_LLM_CONVERSATION_BODY_SIZE = "mockserver.maxLlmConversationBodySize";
    private static final String MOCKSERVER_LLM_PROVIDER = "mockserver.llmProvider";
    private static final String MOCKSERVER_LLM_API_KEY = "mockserver.llmApiKey";
    private static final String MOCKSERVER_LLM_MODEL = "mockserver.llmModel";
    private static final String MOCKSERVER_LLM_BASE_URL = "mockserver.llmBaseUrl";
    private static final String MOCKSERVER_LLM_BACKENDS_CONFIG = "mockserver.llmBackendsConfig";
    private static final String MOCKSERVER_LLM_REQUEST_TIMEOUT_MILLIS = "mockserver.llmRequestTimeoutMillis";
    private static final String MOCKSERVER_DRIFT_SEMANTIC_ANALYSIS_ENABLED = "mockserver.driftSemanticAnalysisEnabled";
    private static final String MOCKSERVER_DRIFT_RESPONSE_TIME_THRESHOLD_MS = "mockserver.driftResponseTimeThresholdMs";
    private static final String MOCKSERVER_FIXTURE_BODY_REDACT_FIELDS = "mockserver.fixtureBodyRedactFields";
    private static final String MOCKSERVER_LLM_VCR_STRICT = "mockserver.llmVcrStrict";
    private static final String MOCKSERVER_OTEL_METRICS_ENABLED = "mockserver.otelMetricsEnabled";
    private static final String MOCKSERVER_OTEL_TRACES_ENABLED = "mockserver.otelTracesEnabled";
    private static final String MOCKSERVER_OTEL_ENDPOINT = "mockserver.otelEndpoint";
    private static final String MOCKSERVER_OTEL_METRICS_EXPORT_INTERVAL_SECONDS = "mockserver.otelMetricsExportIntervalSeconds";
    private static final String MOCKSERVER_OTEL_PROPAGATE_TRACE_CONTEXT = "mockserver.otelPropagateTraceContext";
    private static final String MOCKSERVER_OTEL_GENERATE_TRACE_ID = "mockserver.otelGenerateTraceId";
    private static final String MOCKSERVER_LLM_SEMANTIC_MATCHING_ENABLED = "mockserver.llmSemanticMatchingEnabled";
    private static final String MOCKSERVER_USE_SEMICOLON_AS_QUERY_PARAMETER_SEPARATOR = "mockserver.useSemicolonAsQueryParameterSeparator";
    private static final String MOCKSERVER_ASSUME_ALL_REQUESTS_ARE_HTTP = "mockserver.assumeAllRequestsAreHttp";
    private static final String MOCKSERVER_HTTP2_ENABLED = "mockserver.http2Enabled";

    // matcher safety
    private static final String MOCKSERVER_REGEX_MATCHING_TIMEOUT_MILLIS = "mockserver.regexMatchingTimeoutMillis";
    private static final String MOCKSERVER_XPATH_MATCHING_TIMEOUT_MILLIS = "mockserver.xpathMatchingTimeoutMillis";

    // body matching extensions
    private static final String MOCKSERVER_CUSTOM_JSON_UNIT_MATCHERS_CLASS = "mockserver.customJsonUnitMatchersClass";

    // WASM
    private static final String MOCKSERVER_WASM_ENABLED = "mockserver.wasmEnabled";
    private static final String MOCKSERVER_WASM_MAX_MEMORY_PAGES = "mockserver.wasmMaxMemoryPages";

    // gRPC
    private static final String MOCKSERVER_GRPC_DESCRIPTOR_DIRECTORY = "mockserver.grpcDescriptorDirectory";
    private static final String MOCKSERVER_GRPC_PROTO_DIRECTORY = "mockserver.grpcProtoDirectory";
    private static final String MOCKSERVER_GRPC_ENABLED = "mockserver.grpcEnabled";
    private static final String MOCKSERVER_GRPC_PROTOC_PATH = "mockserver.grpcProtocPath";
    private static final String MOCKSERVER_GRPC_BIDI_STREAMING_ENABLED = "mockserver.grpcBidiStreamingEnabled";
    private static final String MOCKSERVER_DNS_ENABLED = "mockserver.dnsEnabled";
    private static final String MOCKSERVER_DNS_PORT = "mockserver.dnsPort";
    private static final String MOCKSERVER_HTTP3_PORT = "mockserver.http3Port";
    private static final String MOCKSERVER_HTTP3_MAX_IDLE_TIMEOUT = "mockserver.http3MaxIdleTimeout";
    private static final String MOCKSERVER_HTTP3_INITIAL_MAX_DATA = "mockserver.http3InitialMaxData";
    private static final String MOCKSERVER_HTTP3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL = "mockserver.http3InitialMaxStreamDataBidirectional";
    private static final String MOCKSERVER_HTTP3_INITIAL_MAX_STREAMS_BIDIRECTIONAL = "mockserver.http3InitialMaxStreamsBidirectional";
    private static final String MOCKSERVER_HTTP3_QPACK_MAX_TABLE_CAPACITY = "mockserver.http3QpackMaxTableCapacity";
    private static final String MOCKSERVER_HTTP3_CONNECT_UDP_ENABLED = "mockserver.http3ConnectUdpEnabled";
    private static final String MOCKSERVER_HTTP3_ALT_SVC_MAX_AGE = "mockserver.http3AltSvcMaxAge";
    private static final String MOCKSERVER_HTTP3_ADVERTISE_ALT_SVC = "mockserver.http3AdvertiseAltSvc";

    // non http proxying
    private static final String MOCKSERVER_FORWARD_BINARY_REQUESTS_WITHOUT_WAITING_FOR_RESPONSE = "mockserver.forwardBinaryRequestsWithoutWaitingForResponse";

    // streaming proxy
    private static final String MOCKSERVER_STREAMING_RESPONSES_ENABLED = "mockserver.streamingResponsesEnabled";
    private static final String MOCKSERVER_MAX_STREAMING_CAPTURE_BYTES = "mockserver.maxStreamingCaptureBytes";
    private static final String MOCKSERVER_STREAM_IDLE_TIMEOUT_SECONDS = "mockserver.streamIdleTimeoutSeconds";

    // CORS
    private static final String MOCKSERVER_ENABLE_CORS_FOR_API = "mockserver.enableCORSForAPI";
    private static final String MOCKSERVER_ENABLE_CORS_FOR_ALL_RESPONSES = "mockserver.enableCORSForAllResponses";
    private static final String MOCKSERVER_CORS_ALLOW_ORIGIN = "mockserver.corsAllowOrigin";
    private static final String MOCKSERVER_CORS_ALLOW_METHODS = "mockserver.corsAllowMethods";
    private static final String MOCKSERVER_CORS_ALLOW_HEADERS = "mockserver.corsAllowHeaders";
    private static final String MOCKSERVER_CORS_ALLOW_CREDENTIALS = "mockserver.corsAllowCredentials";
    private static final String MOCKSERVER_CORS_MAX_AGE_IN_SECONDS = "mockserver.corsMaxAgeInSeconds";

    // template restrictions
    private static final String MOCKSERVER_JAVASCRIPT_DISALLOWED_CLASSES = "mockserver.javascriptDisallowedClasses";
    private static final String MOCKSERVER_JAVASCRIPT_DISALLOWED_TEXT = "mockserver.javascriptDisallowedText";
    private static final String MOCKSERVER_VELOCITY_DISALLOW_CLASS_LOADING = "mockserver.velocityDisallowClassLoading";
    private static final String MOCKSERVER_VELOCITY_DISALLOWED_TEXT = "mockserver.velocityDisallowedText";
    private static final String MOCKSERVER_MUSTACHE_DISALLOWED_TEXT = "mockserver.mustacheDisallowedText";

    // mock initialization
    private static final String MOCKSERVER_INITIALIZATION_CLASS = "mockserver.initializationClass";
    private static final String MOCKSERVER_INITIALIZATION_JSON_PATH = "mockserver.initializationJsonPath";
    private static final String MOCKSERVER_INITIALIZATION_OPENAPI_PATH = "mockserver.initializationOpenAPIPath";
    private static final String MOCKSERVER_OPENAPI_CONTEXT_PATH_PREFIX = "mockserver.openAPIContextPathPrefix";
    private static final String MOCKSERVER_OPENAPI_RESPONSE_VALIDATION = "mockserver.openAPIResponseValidation";
    private static final String MOCKSERVER_WATCH_INITIALIZATION_JSON = "mockserver.watchInitializationJson";

    // mock persistence
    private static final String MOCKSERVER_PERSIST_EXPECTATIONS = "mockserver.persistExpectations";
    private static final String MOCKSERVER_PERSISTED_EXPECTATIONS_PATH = "mockserver.persistedExpectationsPath";

    // recorded expectation persistence
    private static final String MOCKSERVER_PERSIST_RECORDED_EXPECTATIONS = "mockserver.persistRecordedExpectations";
    private static final String MOCKSERVER_PERSISTED_RECORDED_EXPECTATIONS_PATH = "mockserver.persistedRecordedExpectationsPath";

    // state backend (G10 phase 2a)
    private static final String MOCKSERVER_STATE_BACKEND = "mockserver.stateBackend";
    private static final String MOCKSERVER_BLOB_STORE_TYPE = "mockserver.blobStoreType";

    // cloud blob store configuration
    private static final String MOCKSERVER_BLOB_STORE_BUCKET = "mockserver.blobStoreBucket";
    private static final String MOCKSERVER_BLOB_STORE_REGION = "mockserver.blobStoreRegion";
    private static final String MOCKSERVER_BLOB_STORE_ENDPOINT = "mockserver.blobStoreEndpoint";
    private static final String MOCKSERVER_BLOB_STORE_KEY_PREFIX = "mockserver.blobStoreKeyPrefix";
    private static final String MOCKSERVER_BLOB_STORE_ACCESS_KEY_ID = "mockserver.blobStoreAccessKeyId";
    private static final String MOCKSERVER_BLOB_STORE_SECRET_ACCESS_KEY = "mockserver.blobStoreSecretAccessKey";
    private static final String MOCKSERVER_BLOB_STORE_CONTAINER = "mockserver.blobStoreContainer";
    private static final String MOCKSERVER_BLOB_STORE_CONNECTION_STRING = "mockserver.blobStoreConnectionString";
    private static final String MOCKSERVER_BLOB_STORE_PROJECT_ID = "mockserver.blobStoreProjectId";

    // clustering (G10 phase 2c)
    private static final String MOCKSERVER_CLUSTER_ENABLED = "mockserver.clusterEnabled";
    private static final String MOCKSERVER_CLUSTER_NAME = "mockserver.clusterName";
    private static final String MOCKSERVER_CLUSTER_TRANSPORT_CONFIG = "mockserver.clusterTransportConfig";

    // verification
    private static final String MOCKSERVER_MAXIMUM_NUMBER_OF_REQUESTS_TO_RETURN_IN_VERIFICATION_FAILURE = "mockserver.maximumNumberOfRequestToReturnInVerificationFailure";
    private static final String MOCKSERVER_DETAILED_VERIFICATION_FAILURES = "mockserver.detailedVerificationFailures";

    // proxy
    private static final String MOCKSERVER_ATTEMPT_TO_PROXY_IF_NO_MATCHING_EXPECTATION = "mockserver.attemptToProxyIfNoMatchingExpectation";
    private static final String MOCKSERVER_FORWARD_HTTP_PROXY = "mockserver.forwardHttpProxy";
    private static final String MOCKSERVER_FORWARD_HTTPS_PROXY = "mockserver.forwardHttpsProxy";
    private static final String MOCKSERVER_FORWARD_SOCKS_PROXY = "mockserver.forwardSocksProxy";
    private static final String MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_USERNAME = "mockserver.forwardProxyAuthenticationUsername";
    private static final String MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_PASSWORD = "mockserver.forwardProxyAuthenticationPassword";
    private static final String MOCKSERVER_PROXY_SERVER_REALM = "mockserver.proxyAuthenticationRealm";
    private static final String MOCKSERVER_PROXY_AUTHENTICATION_USERNAME = "mockserver.proxyAuthenticationUsername";
    private static final String MOCKSERVER_PROXY_AUTHENTICATION_PASSWORD = "mockserver.proxyAuthenticationPassword";
    private static final String MOCKSERVER_NO_PROXY_HOSTS = "mockserver.noProxyHosts";
    private static final String MOCKSERVER_PROXY_REMOTE_HOST = "mockserver.proxyRemoteHost";
    private static final String MOCKSERVER_PROXY_REMOTE_PORT = "mockserver.proxyRemotePort";
    private static final String MOCKSERVER_FORWARD_ADJUST_HOST_HEADER = "mockserver.forwardAdjustHostHeader";
    private static final String MOCKSERVER_FORWARD_DEFAULT_HOST_HEADER = "mockserver.forwardDefaultHostHeader";
    private static final String MOCKSERVER_PROXY_PASS = "mockserver.proxyPass";
    private static final String MOCKSERVER_GLOBAL_RESPONSE_DELAY_MILLIS = "mockserver.globalResponseDelayMillis";

    // liveness
    private static final String MOCKSERVER_LIVENESS_HTTP_GET_PATH = "mockserver.livenessHttpGetPath";

    // control plane authentication
    private static final String MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_REQUIRED = "mockserver.controlPlaneTLSMutualAuthenticationRequired";
    private static final String MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN = "mockserver.controlPlaneTLSMutualAuthenticationCAChain";
    private static final String MOCKSERVER_CONTROL_PLANE_TLS_PRIVATE_KEY_PATH = "mockserver.controlPlanePrivateKeyPath";
    private static final String MOCKSERVER_CONTROL_PLANE_TLS_X509_CERTIFICATE_PATH = "mockserver.controlPlaneX509CertificatePath";
    private static final String MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED = "mockserver.controlPlaneJWTAuthenticationRequired";
    private static final String MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_JWK_SOURCE = "mockserver.controlPlaneJWTAuthenticationJWKSource";
    private static final String MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_EXPECTED_AUDIENCE = "mockserver.controlPlaneJWTAuthenticationExpectedAudience";
    private static final String MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_MATCHING_CLAIMS = "mockserver.controlPlaneJWTAuthenticationMatchingClaims";
    private static final String MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED_CLAIMS = "mockserver.controlPlaneJWTAuthenticationRequiredClaims";

    // TLS
    private static final String MOCKSERVER_PROACTIVELY_INITIALISE_TLS = "mockserver.proactivelyInitialiseTLS";
    private static final String MOCKSERVER_TLS_PROTOCOLS = "mockserver.tlsProtocols";
    private static final String MOCKSERVER_TLS_ALLOW_INSECURE_PROTOCOLS = "mockserver.tlsAllowInsecureProtocols";

    // inbound - dynamic CA
    private static final String MOCKSERVER_DYNAMICALLY_CREATE_CERTIFICATE_AUTHORITY_CERTIFICATE = "mockserver.dynamicallyCreateCertificateAuthorityCertificate";
    private static final String MOCKSERVER_CERTIFICATE_DIRECTORY_TO_SAVE_DYNAMIC_SSL_CERTIFICATE = "mockserver.directoryToSaveDynamicSSLCertificate";

    // inbound - dynamic private key & x509
    private static final String MOCKSERVER_PREVENT_CERTIFICATE_DYNAMIC_UPDATE = "mockserver.preventCertificateDynamicUpdate";
    private static final String MOCKSERVER_SSL_CERTIFICATE_DOMAIN_NAME = "mockserver.sslCertificateDomainName";
    private static final String MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_DOMAINS = "mockserver.sslSubjectAlternativeNameDomains";
    private static final String MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_IPS = "mockserver.sslSubjectAlternativeNameIps";

    // inbound - fixed CA
    // inbound - fixed CA
    private static final String MOCKSERVER_CERTIFICATE_AUTHORITY_PRIVATE_KEY = "mockserver.certificateAuthorityPrivateKey";
    private static final String MOCKSERVER_CERTIFICATE_AUTHORITY_X509_CERTIFICATE = "mockserver.certificateAuthorityCertificate";
    public static final String DEFAULT_CERTIFICATE_AUTHORITY_PRIVATE_KEY = "org/mockserver/socket/PKCS8CertificateAuthorityPrivateKey.pem";
    public static final String DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE = "org/mockserver/socket/CertificateAuthorityCertificate.pem";

    // inbound - fixed private key & x509
    private static final String MOCKSERVER_TLS_PRIVATE_KEY_PATH = "mockserver.privateKeyPath";
    private static final String MOCKSERVER_TLS_X509_CERTIFICATE_PATH = "mockserver.x509CertificatePath";

    // inbound - mTLS
    private static final String MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_REQUIRED = "mockserver.tlsMutualAuthenticationRequired";
    private static final String MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN = "mockserver.tlsMutualAuthenticationCertificateChain";

    // outbound - CA
    private static final String MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATES_TRUST_MANAGER_TYPE = "mockserver.forwardProxyTLSX509CertificatesTrustManagerType";

    // outbound - SSRF protection
    private static final String MOCKSERVER_FORWARD_PROXY_BLOCK_PRIVATE_NETWORKS = "mockserver.forwardProxyBlockPrivateNetworks";

    // outbound - fixed CA
    private static final String MOCKSERVER_FORWARD_PROXY_TLS_CUSTOM_TRUST_X509_CERTIFICATES = "mockserver.forwardProxyTLSCustomTrustX509Certificates";

    // outbound - fixed private key & x509
    private static final String MOCKSERVER_FORWARD_PROXY_TLS_PRIVATE_KEY = "mockserver.forwardProxyPrivateKey";
    private static final String MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATE_CHAIN = "mockserver.forwardProxyCertificateChain";

    // service mesh / sidecar
    private static final String MOCKSERVER_TRANSPARENT_PROXY_ENABLED = "mockserver.transparentProxyEnabled";
    private static final String MOCKSERVER_TRANSPARENT_PROXY_TPROXY = "mockserver.transparentProxyTproxy";
    private static final String MOCKSERVER_TRANSPARENT_PROXY_EBPF = "mockserver.transparentProxyEbpf";
    private static final String MOCKSERVER_TRANSPARENT_PROXY_EBPF_MAP_PATH = "mockserver.transparentProxyEbpfMapPath";

    // async messaging defaults
    private static final String MOCKSERVER_ASYNC_KAFKA_BOOTSTRAP_SERVERS = "mockserver.asyncKafkaBootstrapServers";
    private static final String MOCKSERVER_ASYNC_MQTT_BROKER_URL = "mockserver.asyncMqttBrokerUrl";
    private static final String MOCKSERVER_ASYNC_RECORDED_MESSAGE_MAX_ENTRIES = "mockserver.asyncRecordedMessageMaxEntries";

    // properties file
    private static final String MOCKSERVER_PROPERTY_FILE = "mockserver.propertyFile";
    public static final Properties PROPERTIES = readPropertyFile();

    static {
        // Apply the configured log level to java.util.logging once PROPERTIES is loaded.
        // MockServerLogger.<clinit> installs only the default format (it no longer reads
        // ConfigurationProperties), so this is the point at which a configured level
        // (e.g. -Dmockserver.logLevel=DEBUG) is pushed into java.util.logging at startup.
        // The dependency is one-way (ConfigurationProperties -> MockServerLogger), so there
        // is no class-init cycle.
        MockServerLogger.configureLogger();
    }

    private static Map<String, String> slf4jOrJavaLoggerToJavaLoggerLevelMapping;

    private static Map<String, String> slf4jOrJavaLoggerToSLF4JLevelMapping;

    private static Map<String, String> getSLF4JOrJavaLoggerToJavaLoggerLevelMapping() {
        if (slf4jOrJavaLoggerToJavaLoggerLevelMapping == null) {
            slf4jOrJavaLoggerToJavaLoggerLevelMapping = ImmutableMap
                .<String, String>builder()
                .put("TRACE", "FINEST")
                .put("DEBUG", "FINE")
                .put("INFO", "INFO")
                .put("WARN", "WARNING")
                .put("ERROR", "SEVERE")
                .put("FINEST", "FINEST")
                .put("FINE", "FINE")
                .put("WARNING", "WARNING")
                .put("SEVERE", "SEVERE")
                .put("OFF", "OFF")
                .build();
        }
        return slf4jOrJavaLoggerToJavaLoggerLevelMapping;
    }

    private static Map<String, String> getSLF4JOrJavaLoggerToSLF4JLevelMapping() {
        if (slf4jOrJavaLoggerToSLF4JLevelMapping == null) {
            slf4jOrJavaLoggerToSLF4JLevelMapping = ImmutableMap
                .<String, String>builder()
                .put("FINEST", "TRACE")
                .put("FINE", "DEBUG")
                .put("INFO", "INFO")
                .put("WARNING", "WARN")
                .put("SEVERE", "ERROR")
                .put("TRACE", "TRACE")
                .put("DEBUG", "DEBUG")
                .put("WARN", "WARN")
                .put("ERROR", "ERROR")
                .put("OFF", "ERROR")
                .build();
        }
        return slf4jOrJavaLoggerToSLF4JLevelMapping;
    }

    private static String propertyFile() {
        if (isNotBlank(System.getProperty(MOCKSERVER_PROPERTY_FILE)) && System.getProperty(MOCKSERVER_PROPERTY_FILE).equals("/config/mockserver.properties")) {
            return isBlank(System.getenv("MOCKSERVER_PROPERTY_FILE")) ? System.getProperty(MOCKSERVER_PROPERTY_FILE) : System.getenv("MOCKSERVER_PROPERTY_FILE");
        } else {
            return System.getProperty(MOCKSERVER_PROPERTY_FILE, isBlank(System.getenv("MOCKSERVER_PROPERTY_FILE")) ? "mockserver.properties" : System.getenv("MOCKSERVER_PROPERTY_FILE"));
        }
    }

    // logging

    public static Level logLevel() {
        String logLevel = readPropertyHierarchically(PROPERTIES, MOCKSERVER_LOG_LEVEL, "MOCKSERVER_LOG_LEVEL", DEFAULT_LOG_LEVEL).toUpperCase();
        if (isNotBlank(logLevel)) {
            if (getSLF4JOrJavaLoggerToSLF4JLevelMapping().get(logLevel).equals("OFF")) {
                return null;
            } else {
                return Level.valueOf(getSLF4JOrJavaLoggerToSLF4JLevelMapping().get(logLevel));
            }
        } else {
            return Level.INFO;
        }
    }

    public static String javaLoggerLogLevel() {
        String logLevel = readPropertyHierarchically(PROPERTIES, MOCKSERVER_LOG_LEVEL, "MOCKSERVER_LOG_LEVEL", DEFAULT_LOG_LEVEL).toUpperCase();
        if (isNotBlank(logLevel)) {
            if (getSLF4JOrJavaLoggerToJavaLoggerLevelMapping().get(logLevel).equals("OFF")) {
                return "OFF";
            } else {
                return getSLF4JOrJavaLoggerToJavaLoggerLevelMapping().get(logLevel);
            }
        } else {
            return "INFO";
        }
    }

    /**
     * Override the default logging level of INFO
     *
     * @param level the log level, which can be TRACE, DEBUG, INFO, WARN, ERROR, OFF, FINEST, FINE, INFO, WARNING, SEVERE
     */
    public static void logLevel(String level) {
        if (isNotBlank(level)) {
            if (!getSLF4JOrJavaLoggerToSLF4JLevelMapping().containsKey(level)) {
                throw new IllegalArgumentException("log level \"" + level + "\" is not legal it must be one of SL4J levels: \"TRACE\", \"DEBUG\", \"INFO\", \"WARN\", \"ERROR\", \"OFF\", or the Java Logger levels: \"FINEST\", \"FINE\", \"INFO\", \"WARNING\", \"SEVERE\", \"OFF\"");
            }
            setProperty(MOCKSERVER_LOG_LEVEL, level);
        }
        configureLogger();
    }

    public static void temporaryLogLevel(String level, Runnable runnable) {
        Level originalLogLevel = logLevel();
        try {
            logLevel(level);
            runnable.run();
        } finally {
            if (originalLogLevel != null) {
                logLevel(originalLogLevel.name());
            }
        }
    }

    public static boolean disableSystemOut() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DISABLE_SYSTEM_OUT, "MOCKSERVER_DISABLE_SYSTEM_OUT", "" + false));
    }

    /**
     * Disable printing log to system out for JVM, default is enabled
     *
     * @param disable printing log to system out for JVM
     */
    public static void disableSystemOut(boolean disable) {
        setProperty(MOCKSERVER_DISABLE_SYSTEM_OUT, "" + disable);
        configureLogger();
    }

    public static boolean disableLogging() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DISABLE_LOGGING, "MOCKSERVER_DISABLE_LOGGING", "" + false));
    }

    /**
     * Disable all logging and processing of log events
     * <p>
     * The default is false
     *
     * @param disable disable all logging
     */
    public static void disableLogging(boolean disable) {
        setProperty(MOCKSERVER_DISABLE_LOGGING, "" + disable);
        configureLogger();
    }

    public static boolean detailedMatchFailures() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DETAILED_MATCH_FAILURES, "MOCKSERVER_DETAILED_MATCH_FAILURES", "" + true));
    }

    /**
     * If true (the default) the log event recording that a request matcher did not match will include a detailed reason why each non matching field did not match.
     *
     * @param enable enabled detailed match failure log events
     */
    public static void detailedMatchFailures(boolean enable) {
        setProperty(MOCKSERVER_DETAILED_MATCH_FAILURES, "" + enable);
    }

    public static boolean launchUIForLogLevelDebug() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_LAUNCH_UI_FOR_LOG_LEVEL_DEBUG, "MOCKSERVER_LAUNCH_UI_FOR_LOG_LEVEL_DEBUG", "" + false));
    }

    /**
     * If true (the default) the ClientAndServer constructor will open the UI in the default browser when the log level is set to DEBUG.
     *
     * @param enable enabled ClientAndServer constructor launching UI when log level is DEBUG
     */
    public static void launchUIForLogLevelDebug(boolean enable) {
        setProperty(MOCKSERVER_LAUNCH_UI_FOR_LOG_LEVEL_DEBUG, "" + enable);
    }

    public static boolean metricsEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_METRICS_ENABLED, "MOCKSERVER_METRICS_ENABLED", "" + false));
    }

    /**
     * Enable gathering of metrics, default is false
     *
     * @param enable enable metrics
     */
    public static void metricsEnabled(boolean enable) {
        setProperty(MOCKSERVER_METRICS_ENABLED, "" + enable);
    }

    public static long slowRequestThresholdMillis() {
        return readLongProperty(MOCKSERVER_SLOW_REQUEST_THRESHOLD_MILLIS, "MOCKSERVER_SLOW_REQUEST_THRESHOLD_MILLIS", 0L);
    }

    /**
     * Threshold in milliseconds for flagging slow forwarded requests. When a forwarded
     * request's total time exceeds this threshold, a WARN-level log entry is emitted and
     * the {@code mock_server_slow_requests_total} Prometheus counter is incremented.
     * <p>
     * Default is 0 (disabled).
     *
     * @param milliseconds threshold in milliseconds, 0 to disable
     */
    public static void slowRequestThresholdMillis(long milliseconds) {
        setProperty(MOCKSERVER_SLOW_REQUEST_THRESHOLD_MILLIS, "" + milliseconds);
    }

    public static boolean metricsRequestDurationRouteLabels() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_METRICS_REQUEST_DURATION_ROUTE_LABELS, "MOCKSERVER_METRICS_REQUEST_DURATION_ROUTE_LABELS", "" + false));
    }

    /**
     * Enable per-route (HTTP method) latency metrics. When enabled, an additional histogram
     * {@code mock_server_request_duration_by_method_seconds} is registered with a {@code method}
     * label for the HTTP method (GET, POST, etc.), alongside the unlabelled
     * {@code mock_server_request_duration_seconds}. Default is false (no labelled histogram).
     * <p>
     * Cardinality is bounded to the set of standard HTTP methods.
     *
     * @param enable enable method labels on the request duration histogram
     */
    public static void metricsRequestDurationRouteLabels(boolean enable) {
        setProperty(MOCKSERVER_METRICS_REQUEST_DURATION_ROUTE_LABELS, "" + enable);
    }

    public static boolean mcpEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_MCP_ENABLED, "MOCKSERVER_MCP_ENABLED", "" + true));
    }

    /**
     * Enable or disable the MCP (Model Context Protocol) endpoint, default is true
     *
     * @param enable enable MCP endpoint
     */
    public static void mcpEnabled(boolean enable) {
        setProperty(MOCKSERVER_MCP_ENABLED, "" + enable);
    }

    public static boolean wasmEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_WASM_ENABLED, "MOCKSERVER_WASM_ENABLED", "" + false));
    }

    public static void wasmEnabled(boolean enable) {
        setProperty(MOCKSERVER_WASM_ENABLED, "" + enable);
    }

    public static int wasmMaxMemoryPages() {
        return readIntegerProperty(MOCKSERVER_WASM_MAX_MEMORY_PAGES, "MOCKSERVER_WASM_MAX_MEMORY_PAGES", 256);
    }

    public static void wasmMaxMemoryPages(int pages) {
        setProperty(MOCKSERVER_WASM_MAX_MEMORY_PAGES, "" + pages);
    }

    public static String grpcDescriptorDirectory() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_GRPC_DESCRIPTOR_DIRECTORY, "MOCKSERVER_GRPC_DESCRIPTOR_DIRECTORY", "");
    }

    public static void grpcDescriptorDirectory(String directory) {
        setProperty(MOCKSERVER_GRPC_DESCRIPTOR_DIRECTORY, directory);
    }

    public static String grpcProtoDirectory() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_GRPC_PROTO_DIRECTORY, "MOCKSERVER_GRPC_PROTO_DIRECTORY", "");
    }

    public static void grpcProtoDirectory(String directory) {
        setProperty(MOCKSERVER_GRPC_PROTO_DIRECTORY, directory);
    }

    public static boolean grpcEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_GRPC_ENABLED, "MOCKSERVER_GRPC_ENABLED", "" + true));
    }

    public static void grpcEnabled(boolean enable) {
        setProperty(MOCKSERVER_GRPC_ENABLED, "" + enable);
    }

    public static String grpcProtocPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_GRPC_PROTOC_PATH, "MOCKSERVER_GRPC_PROTOC_PATH", "protoc");
    }

    public static void grpcProtocPath(String path) {
        setProperty(MOCKSERVER_GRPC_PROTOC_PATH, path);
    }

    public static boolean grpcBidiStreamingEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_GRPC_BIDI_STREAMING_ENABLED, "MOCKSERVER_GRPC_BIDI_STREAMING_ENABLED", "false"));
    }

    /**
     * If true the HTTP/2 pipeline uses Http2FrameCodec + Http2MultiplexHandler instead of
     * HttpToHttp2ConnectionHandler + InboundHttp2ToHttpAdapter for connections where gRPC
     * descriptors are loaded. This is required for true client-streaming and bidirectional-streaming
     * gRPC in a future phase. In Phase 0 the multiplex branch re-aggregates frames so behaviour
     * is identical to the connection-level adapter.
     * <p>
     * Requires gRPC descriptors to be loaded (grpcEnabled with descriptors present). When false
     * (the default) or when no descriptors are loaded, the existing connection-level adapter is used.
     * <p>
     * Default is false
     *
     * @param enable enable the multiplex HTTP/2 pipeline for gRPC bidi-streaming support
     */
    public static void grpcBidiStreamingEnabled(boolean enable) {
        setProperty(MOCKSERVER_GRPC_BIDI_STREAMING_ENABLED, "" + enable);
    }

    public static boolean dnsEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DNS_ENABLED, "MOCKSERVER_DNS_ENABLED", "" + false));
    }

    public static void dnsEnabled(boolean enable) {
        setProperty(MOCKSERVER_DNS_ENABLED, "" + enable);
    }

    public static int dnsPort() {
        return readIntegerProperty(MOCKSERVER_DNS_PORT, "MOCKSERVER_DNS_PORT", 0);
    }

    public static void dnsPort(int port) {
        setProperty(MOCKSERVER_DNS_PORT, "" + port);
    }

    // experimental HTTP/3 (QUIC)

    public static int http3Port() {
        return readIntegerProperty(MOCKSERVER_HTTP3_PORT, "MOCKSERVER_HTTP3_PORT", 0);
    }

    public static void http3Port(int port) {
        setProperty(MOCKSERVER_HTTP3_PORT, "" + port);
    }

    /**
     * Max idle timeout in milliseconds for QUIC connections.
     * Default: 5000 (5 seconds).
     */
    public static long http3MaxIdleTimeout() {
        return Math.max(0, readLongProperty(MOCKSERVER_HTTP3_MAX_IDLE_TIMEOUT, "MOCKSERVER_HTTP3_MAX_IDLE_TIMEOUT", 5000L));
    }

    public static void http3MaxIdleTimeout(long millis) {
        setProperty(MOCKSERVER_HTTP3_MAX_IDLE_TIMEOUT, "" + millis);
    }

    /**
     * Initial maximum data (connection-level flow control) in bytes.
     * Default: 10000000 (10 MB).
     */
    public static long http3InitialMaxData() {
        return Math.max(0, readLongProperty(MOCKSERVER_HTTP3_INITIAL_MAX_DATA, "MOCKSERVER_HTTP3_INITIAL_MAX_DATA", 10000000L));
    }

    public static void http3InitialMaxData(long bytes) {
        setProperty(MOCKSERVER_HTTP3_INITIAL_MAX_DATA, "" + bytes);
    }

    /**
     * Initial maximum stream data for bidirectional streams (per-stream flow control)
     * in bytes. Applied to both local and remote bidirectional streams.
     * Default: 1000000 (1 MB).
     */
    public static long http3InitialMaxStreamDataBidirectional() {
        return Math.max(0, readLongProperty(MOCKSERVER_HTTP3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL, "MOCKSERVER_HTTP3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL", 1000000L));
    }

    public static void http3InitialMaxStreamDataBidirectional(long bytes) {
        setProperty(MOCKSERVER_HTTP3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL, "" + bytes);
    }

    /**
     * Initial maximum number of concurrent bidirectional streams.
     * Default: 100.
     */
    public static long http3InitialMaxStreamsBidirectional() {
        return Math.max(0, readLongProperty(MOCKSERVER_HTTP3_INITIAL_MAX_STREAMS_BIDIRECTIONAL, "MOCKSERVER_HTTP3_INITIAL_MAX_STREAMS_BIDIRECTIONAL", 100L));
    }

    public static void http3InitialMaxStreamsBidirectional(long maxStreams) {
        setProperty(MOCKSERVER_HTTP3_INITIAL_MAX_STREAMS_BIDIRECTIONAL, "" + maxStreams);
    }

    /**
     * QPACK dynamic table maximum capacity in bytes. Controls the amount of
     * memory allocated for QPACK header compression on the HTTP/3 control stream.
     * Set to 0 to disable the dynamic table entirely.
     * Default: 0 (dynamic table disabled — only static table used).
     */
    public static long http3QpackMaxTableCapacity() {
        return Math.max(0, readLongProperty(MOCKSERVER_HTTP3_QPACK_MAX_TABLE_CAPACITY, "MOCKSERVER_HTTP3_QPACK_MAX_TABLE_CAPACITY", 0L));
    }

    public static void http3QpackMaxTableCapacity(long bytes) {
        setProperty(MOCKSERVER_HTTP3_QPACK_MAX_TABLE_CAPACITY, "" + bytes);
    }

    /**
     * Enable the CONNECT-UDP (MASQUE) forward proxy handler on the HTTP/3 server.
     * When enabled, HTTP/3 CONNECT requests are intercepted by a dedicated handler
     * that currently returns 501 Not Implemented (the bundled QUIC codec does not yet
     * support the :protocol pseudo-header needed for the relay). This is experimental and
     * requires codec support for the :protocol pseudo-header (RFC 9220) and HTTP
     * Datagrams (RFC 9297). Currently the bundled netty-incubator-codec-http3
     * (0.0.30.Final) does not support extended CONNECT, so enabling this flag will
     * cause CONNECT requests to be cleanly rejected with 501 Not Implemented.
     * Default: false (disabled).
     */
    public static boolean http3ConnectUdpEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_HTTP3_CONNECT_UDP_ENABLED, "MOCKSERVER_HTTP3_CONNECT_UDP_ENABLED", "" + false));
    }

    public static void http3ConnectUdpEnabled(boolean enabled) {
        setProperty(MOCKSERVER_HTTP3_CONNECT_UDP_ENABLED, "" + enabled);
    }

    /**
     * Max-age in seconds for the Alt-Svc header advertising HTTP/3 on the TCP
     * response path. Only relevant when {@code http3Port > 0} and
     * {@code http3AdvertiseAltSvc} is {@code true}.
     * Default: 86400 (24 hours).
     */
    public static long http3AltSvcMaxAge() {
        return Math.max(0, readLongProperty(MOCKSERVER_HTTP3_ALT_SVC_MAX_AGE, "MOCKSERVER_HTTP3_ALT_SVC_MAX_AGE", 86400L));
    }

    public static void http3AltSvcMaxAge(long seconds) {
        setProperty(MOCKSERVER_HTTP3_ALT_SVC_MAX_AGE, "" + seconds);
    }

    /**
     * Whether to add an {@code Alt-Svc: h3=":<http3Port>"; ma=<maxAge>} header
     * to every response served over the TCP (HTTP/1.1 and HTTP/2) paths when
     * {@code http3Port > 0}. When {@code false}, no Alt-Svc header is added
     * even when HTTP/3 is enabled (useful for testing without client auto-upgrade).
     * Default: true.
     */
    public static boolean http3AdvertiseAltSvc() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_HTTP3_ADVERTISE_ALT_SVC, "MOCKSERVER_HTTP3_ADVERTISE_ALT_SVC", "" + true));
    }

    public static void http3AdvertiseAltSvc(boolean advertise) {
        setProperty(MOCKSERVER_HTTP3_ADVERTISE_ALT_SVC, "" + advertise);
    }

    // service mesh / sidecar

    public static boolean transparentProxyEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_TRANSPARENT_PROXY_ENABLED, "MOCKSERVER_TRANSPARENT_PROXY_ENABLED", "" + false));
    }

    public static void transparentProxyEnabled(boolean enable) {
        setProperty(MOCKSERVER_TRANSPARENT_PROXY_ENABLED, "" + enable);
    }

    /**
     * Enable TPROXY (IP_TRANSPARENT) mode for transparent proxy original destination
     * resolution. When enabled, the listener socket is bound with IP_TRANSPARENT and
     * the original destination is read from the socket's local address (preserved by
     * the TPROXY iptables target). Requires Linux, epoll transport, CAP_NET_ADMIN,
     * and TPROXY iptables rules instead of REDIRECT. Default: false.
     */
    public static boolean transparentProxyTproxy() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_TRANSPARENT_PROXY_TPROXY, "MOCKSERVER_TRANSPARENT_PROXY_TPROXY", "" + false));
    }

    public static void transparentProxyTproxy(boolean enable) {
        setProperty(MOCKSERVER_TRANSPARENT_PROXY_TPROXY, "" + enable);
    }

    /**
     * Enable eBPF-based original destination resolution. When enabled, the resolver
     * reads from a pinned BPF hash map (populated by an external cgroup/connect4
     * BPF program) keyed by socket cookie. Requires Linux, CAP_BPF, a BTF-enabled
     * kernel, and the external BPF program. Default: false.
     */
    public static boolean transparentProxyEbpf() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_TRANSPARENT_PROXY_EBPF, "MOCKSERVER_TRANSPARENT_PROXY_EBPF", "" + false));
    }

    public static void transparentProxyEbpf(boolean enable) {
        setProperty(MOCKSERVER_TRANSPARENT_PROXY_EBPF, "" + enable);
    }

    /**
     * Path to the pinned BPF map used by the eBPF original destination resolver.
     * The map must be a BPF hash map with u64 key (socket cookie) and 6-byte value
     * (4-byte IPv4 address + 2-byte port in network byte order).
     * Default: /sys/fs/bpf/mockserver_orig_dst.
     */
    public static String transparentProxyEbpfMapPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_TRANSPARENT_PROXY_EBPF_MAP_PATH, "MOCKSERVER_TRANSPARENT_PROXY_EBPF_MAP_PATH", "/sys/fs/bpf/mockserver_orig_dst");
    }

    public static void transparentProxyEbpfMapPath(String path) {
        setProperty(MOCKSERVER_TRANSPARENT_PROXY_EBPF_MAP_PATH, path);
    }

    // async messaging defaults

    /**
     * Default Kafka bootstrap servers used when a {@code PUT /mockserver/asyncapi}
     * request body does not include {@code brokerConfig.kafkaBootstrapServers}.
     * Empty string means no default (broker must be specified per-request).
     */
    public static String asyncKafkaBootstrapServers() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_ASYNC_KAFKA_BOOTSTRAP_SERVERS, "MOCKSERVER_ASYNC_KAFKA_BOOTSTRAP_SERVERS", "");
    }

    public static void asyncKafkaBootstrapServers(String servers) {
        setProperty(MOCKSERVER_ASYNC_KAFKA_BOOTSTRAP_SERVERS, servers);
    }

    /**
     * Default MQTT broker URL used when a {@code PUT /mockserver/asyncapi}
     * request body does not include {@code brokerConfig.mqttBrokerUrl}.
     * Empty string means no default (broker must be specified per-request).
     */
    public static String asyncMqttBrokerUrl() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_ASYNC_MQTT_BROKER_URL, "MOCKSERVER_ASYNC_MQTT_BROKER_URL", "");
    }

    public static void asyncMqttBrokerUrl(String url) {
        setProperty(MOCKSERVER_ASYNC_MQTT_BROKER_URL, url);
    }

    /**
     * Maximum number of recorded messages retained per channel in async
     * messaging subscribers. Default is 1000.
     */
    public static int asyncRecordedMessageMaxEntries() {
        return readIntegerProperty(MOCKSERVER_ASYNC_RECORDED_MESSAGE_MAX_ENTRIES, "MOCKSERVER_ASYNC_RECORDED_MESSAGE_MAX_ENTRIES", 1000);
    }

    public static void asyncRecordedMessageMaxEntries(int maxEntries) {
        setProperty(MOCKSERVER_ASYNC_RECORDED_MESSAGE_MAX_ENTRIES, "" + maxEntries);
    }

    public static Map<String, String> logLevelOverrides() {
        String overridesJson = readPropertyHierarchically(PROPERTIES, MOCKSERVER_LOG_LEVEL_OVERRIDES, "MOCKSERVER_LOG_LEVEL_OVERRIDES", "");
        if (isNotBlank(overridesJson)) {
            try {
                return ObjectMapperFactory.createObjectMapper().readValue(overridesJson, new TypeReference<Map<String, String>>() {
                });
            } catch (Exception e) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("invalid value for logLevelOverrides, expected JSON map but found:{}")
                        .setArguments(overridesJson)
                );
                return Collections.emptyMap();
            }
        } else {
            return Collections.emptyMap();
        }
    }

    /**
     * Override the log level for specific log message type categories or individual log message types.
     * <p>
     * Keys can be category group names (MATCHING, REQUEST_LIFECYCLE, EXPECTATION_MANAGEMENT, VERIFICATION, SERVER, GENERAL)
     * or individual LogMessageType names (e.g., EXPECTATION_NOT_MATCHED, FORWARDED_REQUEST).
     * Values are SLF4J log level names (TRACE, DEBUG, INFO, WARN, ERROR).
     * Resolution order: individual type override > category group override > global logLevel.
     *
     * @param overrides map of category/type names to log level names
     */
    public static void logLevelOverrides(Map<String, String> overrides) {
        if (overrides != null && !overrides.isEmpty()) {
            try {
                setProperty(MOCKSERVER_LOG_LEVEL_OVERRIDES, ObjectMapperFactory.createObjectMapper().writeValueAsString(overrides));
            } catch (Exception e) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("failed to serialize logLevelOverrides:{}")
                        .setArguments(overrides)
                );
            }
        } else {
            clearProperty(MOCKSERVER_LOG_LEVEL_OVERRIDES);
        }
    }

    public static boolean compactLogFormat() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_COMPACT_LOG_FORMAT, "MOCKSERVER_COMPACT_LOG_FORMAT", "" + false));
    }

    /**
     * When enabled, log messages written to stdout/SLF4J use a compact single-line format showing
     * summary information (e.g., method, path, status code, expectation ID) instead of full
     * JSON-serialized request and response details. The dashboard UI, verification, and log
     * retrieval APIs are not affected.
     *
     * @param enable enable compact log format
     */
    public static void compactLogFormat(boolean enable) {
        setProperty(MOCKSERVER_COMPACT_LOG_FORMAT, "" + enable);
    }

    // memory usage

    public static long heapAvailableInKB() {
        Summary heap = MemoryMonitoring.getJVMMemory(MemoryType.HEAP);
        long baseMemory  = 20 * 1024L;
        return ((heap.getNet().getMax() - heap.getNet().getUsed()) / 1024L) - baseMemory;
    }

    public static int maxExpectations() {
        return readIntegerProperty(MOCKSERVER_MAX_EXPECTATIONS, "MOCKSERVER_MAX_EXPECTATIONS", Math.min((int) (heapAvailableInKB() / 10), 15000));
    }

    /**
     * <p>
     * Maximum number of expectations stored in memory.  Expectations are stored in a circular queue so once this limit is reach the oldest and lowest priority expectations are overwritten
     * </p>
     * <p>
     * The default maximum depends on the available memory in the JVM with an upper limit of 15000
     * </p>
     *
     * @param count maximum number of expectations to store
     */
    public static void maxExpectations(int count) {
        setProperty(MOCKSERVER_MAX_EXPECTATIONS, "" + count);
    }

    public static int maxLogEntries() {
        return readIntegerProperty(MOCKSERVER_MAX_LOG_ENTRIES, "MOCKSERVER_MAX_LOG_ENTRIES", Math.min((int) (heapAvailableInKB() / 8), 100000));
    }

    /**
     * <p>
     * Maximum number of log entries stored in memory.  Log entries are stored in a circular queue so once this limit is reach the oldest log entries are overwritten.
     * </p>
     * <p>
     * The default maximum depends on the available memory in the JVM with an upper limit of 100000, but can be overridden using defaultMaxLogEntries
     * </p>
     *
     * @param count maximum number of expectations to store
     */
    public static void maxLogEntries(int count) {
        setProperty(MOCKSERVER_MAX_LOG_ENTRIES, "" + count);
    }

    public static int maxWebSocketExpectations() {
        return readIntegerProperty(MOCKSERVER_MAX_WEB_SOCKET_EXPECTATIONS, "MOCKSERVER_MAX_WEB_SOCKET_EXPECTATIONS", 1500);
    }

    /**
     * <p>
     * Maximum number of remote (not the same JVM) method callbacks (i.e. web sockets) registered for expectations.  The web socket client registry entries are stored in a circular queue so once this limit is reach the oldest are overwritten.
     * </p>
     * <p>
     * The default is 1500
     * </p>
     *
     * @param count maximum number of method callbacks (i.e. web sockets) registered for expectations
     */
    public static void maxWebSocketExpectations(int count) {
        setProperty(MOCKSERVER_MAX_WEB_SOCKET_EXPECTATIONS, "" + count);
    }

    public static boolean outputMemoryUsageCsv() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_OUTPUT_MEMORY_USAGE_CSV, "MOCKSERVER_OUTPUT_MEMORY_USAGE_CSV", "false"));
    }

    /**
     * <p>Output JVM memory usage metrics to CSV file periodically called <strong>memoryUsage_&lt;yyyy-MM-dd&gt;.csv</strong></p>
     *
     * @param enable output of JVM memory metrics
     */
    public static void outputMemoryUsageCsv(boolean enable) {
        setProperty(MOCKSERVER_OUTPUT_MEMORY_USAGE_CSV, "" + enable);
    }

    public static String memoryUsageCsvDirectory() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_MEMORY_USAGE_CSV_DIRECTORY, "MOCKSERVER_MEMORY_USAGE_CSV_DIRECTORY", ".");
    }

    /**
     * <p>Directory to output JVM memory usage metrics CSV files to when outputMemoryUsageCsv enabled</p>
     *
     * @param directory directory to save JVM memory metrics CSV files
     */
    public static void memoryUsageCsvDirectory(String directory) {
        fileExists(directory);
        setProperty(MOCKSERVER_MEMORY_USAGE_CSV_DIRECTORY, directory);
    }

    // scalability

    public static boolean useNativeTransport() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_USE_NATIVE_TRANSPORT, "MOCKSERVER_USE_NATIVE_TRANSPORT", "" + true));
    }

    /**
     * If true (the default) MockServer will use the native epoll transport on Linux
     * for higher performance and to enable transparent-proxy SO_ORIGINAL_DST resolution.
     * Set to false to force the NIO transport on all platforms.
     * <p>
     * This property is read at start-up only.
     *
     * @param enable enable native transport when available
     */
    public static void useNativeTransport(boolean enable) {
        setProperty(MOCKSERVER_USE_NATIVE_TRANSPORT, "" + enable);
    }

    public static int nioEventLoopThreadCount() {
        return readIntegerProperty(MOCKSERVER_NIO_EVENT_LOOP_THREAD_COUNT, "MOCKSERVER_NIO_EVENT_LOOP_THREAD_COUNT", 5);
    }

    /**
     * <p>Netty worker thread pool size for handling requests and response.  These threads are used for fast non-blocking activities such as, reading and de-serialise all requests and responses.</p>
     *
     * @param count Netty worker thread pool size
     */
    public static void nioEventLoopThreadCount(int count) {
        setProperty(MOCKSERVER_NIO_EVENT_LOOP_THREAD_COUNT, "" + count);
    }

    public static int actionHandlerThreadCount() {
        return readIntegerProperty(MOCKSERVER_ACTION_HANDLER_THREAD_COUNT, "MOCKSERVER_ACTION_HANDLER_THREAD_COUNT", Math.max(5, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * <p>Number of threads for the action handler thread pool</p>
     * <p>These threads are used for handling actions such as:</p>
     *     <ul>
     *         <li>serialising and writing expectation or proxied responses</li>
     *         <li>handling response delays in a non-blocking way (i.e. using a scheduler)</li>
     *         <li>executing class callbacks</li>
     *         <li>handling method / closure callbacks (using web sockets)</li>
     *     </ul>
     * <p>
     * <p>Default is maximum of 5 or available processors count</p>
     *
     * @param count Netty worker thread pool size
     */
    public static void actionHandlerThreadCount(int count) {
        setProperty(MOCKSERVER_ACTION_HANDLER_THREAD_COUNT, "" + count);
    }

    public static int clientNioEventLoopThreadCount() {
        return readIntegerProperty(MOCKSERVER_CLIENT_NIO_EVENT_LOOP_THREAD_COUNT, "MOCKSERVER_CLIENT_NIO_EVENT_LOOP_THREAD_COUNT", 5);
    }

    /**
     * <p>Client Netty worker thread pool size for handling requests and response.  These threads handle deserializing and serialising HTTP requests and responses and some other fast logic.</p>
     *
     * <p>Default is 5 threads</p>
     *
     * @param count Client Netty worker thread pool size
     */
    public static void clientNioEventLoopThreadCount(int count) {
        setProperty(MOCKSERVER_CLIENT_NIO_EVENT_LOOP_THREAD_COUNT, "" + count);
    }

    public static int webSocketClientEventLoopThreadCount() {
        return readIntegerProperty(MOCKSERVER_WEB_SOCKET_CLIENT_EVENT_LOOP_THREAD_COUNT, "MOCKSERVER_WEB_SOCKET_CLIENT_EVENT_LOOP_THREAD_COUNT", 5);
    }

    /**
     * <p>Web socket thread pool size for expectations with remote (not the same JVM) method callbacks (i.e. web sockets).</p>
     * <p>
     * Default is 5 threads
     *
     * @param count web socket worker thread pool size
     */
    public static void webSocketClientEventLoopThreadCount(int count) {
        setProperty(MOCKSERVER_WEB_SOCKET_CLIENT_EVENT_LOOP_THREAD_COUNT, "" + count);
    }

    public static long maxFutureTimeout() {
        return readLongProperty(MOCKSERVER_MAX_FUTURE_TIMEOUT, "MOCKSERVER_MAX_FUTURE_TIMEOUT", TimeUnit.SECONDS.toMillis(90));
    }

    /**
     * Maximum time allowed in milliseconds for any future to wait, for example when waiting for a response over a web socket callback.
     * <p>
     * Default is 90,000 ms
     *
     * @param milliseconds maximum time allowed in milliseconds
     */
    public static void maxFutureTimeout(long milliseconds) {
        setProperty(MOCKSERVER_MAX_FUTURE_TIMEOUT, "" + milliseconds);
    }

    public static boolean matchersFailFast() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_MATCHERS_FAIL_FAST, "MOCKSERVER_MATCHERS_FAIL_FAST", "" + true));
    }

    /**
     * If true (the default) request matchers will fail on the first non-matching field, if false request matchers will compare all fields.
     * This is useful to see all mismatching fields in the log event recording that a request matcher did not match.
     *
     * @param enable enabled request matchers failing fast
     */
    public static void matchersFailFast(boolean enable) {
        setProperty(MOCKSERVER_MATCHERS_FAIL_FAST, "" + enable);
    }

    // socket

    public static long maxSocketTimeout() {
        return readLongProperty(MOCKSERVER_MAX_SOCKET_TIMEOUT, "MOCKSERVER_MAX_SOCKET_TIMEOUT", TimeUnit.SECONDS.toMillis(20));
    }

    /**
     * Maximum time in milliseconds allowed for a response from a socket
     * <p>
     * Default is 20,000 ms
     *
     * @param milliseconds maximum time in milliseconds allowed
     */
    public static void maxSocketTimeout(long milliseconds) {
        setProperty(MOCKSERVER_MAX_SOCKET_TIMEOUT, "" + milliseconds);
    }

    public static long socketConnectionTimeout() {
        return readLongProperty(MOCKSERVER_SOCKET_CONNECTION_TIMEOUT, "MOCKSERVER_SOCKET_CONNECTION_TIMEOUT", TimeUnit.SECONDS.toMillis(20));
    }

    /**
     * Maximum time in milliseconds allowed to connect to a socket
     * <p>
     * Default is 20,000 ms
     *
     * @param milliseconds maximum time allowed in milliseconds
     */
    public static void socketConnectionTimeout(long milliseconds) {
        setProperty(MOCKSERVER_SOCKET_CONNECTION_TIMEOUT, "" + milliseconds);
    }

    public static long connectionDelayMillis() {
        return readLongProperty(MOCKSERVER_CONNECTION_DELAY_MILLIS, "MOCKSERVER_CONNECTION_DELAY_MILLIS", 0L);
    }

    public static void connectionDelayMillis(long milliseconds) {
        setProperty(MOCKSERVER_CONNECTION_DELAY_MILLIS, "" + milliseconds);
    }

    /**
     * <p>If true socket connections will always be closed after a response is returned, if false connection is only closed if request header indicate connection should be closed.</p>
     * <p>
     * Default is false
     *
     * @param alwaysClose true socket connections will always be closed after a response is returned
     */
    public static void alwaysCloseSocketConnections(boolean alwaysClose) {
        setProperty(MOCKSERVER_ALWAYS_CLOSE_SOCKET_CONNECTIONS, "" + alwaysClose);
    }

    public static boolean alwaysCloseSocketConnections() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_ALWAYS_CLOSE_SOCKET_CONNECTIONS, "MOCKSERVER_ALWAYS_CLOSE_SOCKET_CONNECTIONS", "false"));
    }

    // streaming proxy

    public static boolean streamingResponsesEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_STREAMING_RESPONSES_ENABLED, "MOCKSERVER_STREAMING_RESPONSES_ENABLED", "true"));
    }

    /**
     * If true (the default) streaming responses (Server-Sent Events with {@code Content-Type: text/event-stream})
     * received while proxying are relayed to the client incrementally as they arrive, instead of being fully
     * buffered before being forwarded. This keeps streaming APIs (such as LLM APIs) responsive when proxied.
     * Only SSE responses are detected as streaming; ordinary chunked responses are aggregated normally.
     * <p>
     * Default is true
     *
     * @param enable enable incremental relay of streaming responses while proxying
     */
    public static void streamingResponsesEnabled(boolean enable) {
        setProperty(MOCKSERVER_STREAMING_RESPONSES_ENABLED, "" + enable);
    }

    public static int maxStreamingCaptureBytes() {
        return Math.max(0, readIntegerProperty(MOCKSERVER_MAX_STREAMING_CAPTURE_BYTES, "MOCKSERVER_MAX_STREAMING_CAPTURE_BYTES", 262144));
    }

    /**
     * The maximum number of bytes of a streaming response body captured into the event log while relaying it.
     * The full stream is always relayed to the client; this only bounds how much is retained for the dashboard
     * and retrieve API. Once exceeded the logged body is truncated and flagged.
     * <p>
     * Default is 262144 (256 KB)
     *
     * @param bytes maximum number of streaming response body bytes captured into the event log
     */
    public static void maxStreamingCaptureBytes(int bytes) {
        setProperty(MOCKSERVER_MAX_STREAMING_CAPTURE_BYTES, "" + bytes);
    }

    public static int streamIdleTimeoutSeconds() {
        return Math.max(0, readIntegerProperty(MOCKSERVER_STREAM_IDLE_TIMEOUT_SECONDS, "MOCKSERVER_STREAM_IDLE_TIMEOUT_SECONDS", 60));
    }

    /**
     * The maximum time in seconds a streaming response connection may be idle (no chunk received) before it is
     * considered dead and closed. This replaces the fixed socket timeout for streaming responses, which would
     * otherwise terminate long-lived streams.
     * <p>
     * Default is 60 seconds
     *
     * @param seconds maximum idle time in seconds between streaming response chunks
     */
    public static void streamIdleTimeoutSeconds(int seconds) {
        setProperty(MOCKSERVER_STREAM_IDLE_TIMEOUT_SECONDS, "" + seconds);
    }

    public static String localBoundIP() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LOCAL_BOUND_IP, "MOCKSERVER_LOCAL_BOUND_IP", "");
    }

    /**
     * The local IP address to bind to for accepting new socket connections
     * <p>
     * Default is 0.0.0.0
     *
     * @param localBoundIP local IP address to bind to for accepting new socket connections
     */
    public static void localBoundIP(String localBoundIP) {
        if (isNotBlank(localBoundIP)) {
            setProperty(MOCKSERVER_LOCAL_BOUND_IP, InetAddresses.forString(localBoundIP).getHostAddress());
        }
    }

    // http request parsing

    public static int maxInitialLineLength() {
        return readIntegerProperty(MOCKSERVER_MAX_INITIAL_LINE_LENGTH, "MOCKSERVER_MAX_INITIAL_LINE_LENGTH", Integer.MAX_VALUE);
    }

    /**
     * Maximum size of the first line of an HTTP request
     * <p>
     * The default is Integer.MAX_VALUE
     *
     * @param length maximum size of the first line of an HTTP request
     */
    public static void maxInitialLineLength(int length) {
        setProperty(MOCKSERVER_MAX_INITIAL_LINE_LENGTH, "" + length);
    }

    public static int maxHeaderSize() {
        return readIntegerProperty(MOCKSERVER_MAX_HEADER_SIZE, "MOCKSERVER_MAX_HEADER_SIZE", Integer.MAX_VALUE);
    }

    /**
     * Maximum size of HTTP request headers
     * <p>
     * The default is Integer.MAX_VALUE
     *
     * @param size maximum size of HTTP request headers
     */
    public static void maxHeaderSize(int size) {
        setProperty(MOCKSERVER_MAX_HEADER_SIZE, "" + size);
    }

    public static int maxChunkSize() {
        return readIntegerProperty(MOCKSERVER_MAX_CHUNK_SIZE, "MOCKSERVER_MAX_CHUNK_SIZE", Integer.MAX_VALUE);
    }

    /**
     * Maximum size of HTTP chunks in request or responses
     * <p>
     * The default is Integer.MAX_VALUE
     *
     * @param size maximum size of HTTP chunks in request or responses
     */
    public static void maxChunkSize(int size) {
        setProperty(MOCKSERVER_MAX_CHUNK_SIZE, "" + size);
    }

    public static int maxRequestBodySize() {
        return readIntegerProperty(MOCKSERVER_MAX_REQUEST_BODY_SIZE, "MOCKSERVER_MAX_REQUEST_BODY_SIZE", 10 * 1024 * 1024);
    }

    /**
     * Maximum aggregated body size (in bytes) accepted on inbound HTTP/1.1 and HTTP/2 requests
     * before MockServer responds with 413 Payload Too Large.
     * <p>
     * The default is 10,485,760 bytes (10 MiB). Raise this only if you intentionally mock
     * large uploads; very large limits make MockServer susceptible to memory exhaustion.
     *
     * @param size maximum inbound request body size in bytes
     */
    public static void maxRequestBodySize(int size) {
        setProperty(MOCKSERVER_MAX_REQUEST_BODY_SIZE, "" + size);
    }

    public static int maxResponseBodySize() {
        return readIntegerProperty(MOCKSERVER_MAX_RESPONSE_BODY_SIZE, "MOCKSERVER_MAX_RESPONSE_BODY_SIZE", 50 * 1024 * 1024);
    }

    /**
     * Maximum aggregated body size (in bytes) accepted on responses received from upstream
     * servers when MockServer is acting as a proxy or forwarder.
     * <p>
     * The default is 52,428,800 bytes (50 MiB).
     *
     * @param size maximum upstream response body size in bytes
     */
    public static void maxResponseBodySize(int size) {
        setProperty(MOCKSERVER_MAX_RESPONSE_BODY_SIZE, "" + size);
    }

    public static int maxLlmConversationBodySize() {
        int value = readIntegerProperty(MOCKSERVER_MAX_LLM_CONVERSATION_BODY_SIZE, "MOCKSERVER_MAX_LLM_CONVERSATION_BODY_SIZE", 1048576);
        if (value < 16384) {
            if (LoggerHolder.LOGGER != null) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setMessageFormat("maxLlmConversationBodySize value {} is below minimum, clamping to 16384")
                        .setArguments(value)
                );
            }
            return 16384;
        }
        if (value > 67108864) {
            if (LoggerHolder.LOGGER != null) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setMessageFormat("maxLlmConversationBodySize value {} is above maximum, clamping to 67108864")
                        .setArguments(value)
                );
            }
            return 67108864;
        }
        return value;
    }

    /**
     * Maximum body size (in bytes) for LLM conversation request bodies.
     * <p>
     * The default is 1,048,576 bytes (1 MiB). Valid range is [16384, 67108864].
     * Values outside this range are silently clamped.
     *
     * @param size maximum LLM conversation body size in bytes
     */
    public static void maxLlmConversationBodySize(int size) {
        setProperty(MOCKSERVER_MAX_LLM_CONVERSATION_BODY_SIZE, "" + size);
    }

    /**
     * Provider type for the default runtime-LLM backend (one of the
     * {@link org.mockserver.model.Provider} enum names). Runtime-LLM features
     * (drift detection, semantic matching) are off unless a backend resolves;
     * this is layer 2 of backend resolution (single default backend). Empty by
     * default.
     */
    public static String llmProvider() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_PROVIDER, "MOCKSERVER_LLM_PROVIDER", "");
    }

    public static void llmProvider(String provider) {
        setProperty(MOCKSERVER_LLM_PROVIDER, provider);
    }

    /**
     * API key (secret) for the default runtime-LLM backend. Never logged or
     * emitted in config dumps — see {@link org.mockserver.llm.client.LlmBackend}.
     */
    public static String llmApiKey() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_API_KEY, "MOCKSERVER_LLM_API_KEY", "");
    }

    public static void llmApiKey(String apiKey) {
        setProperty(MOCKSERVER_LLM_API_KEY, apiKey);
    }

    /**
     * Model for the default runtime-LLM backend; empty means the per-provider
     * default applies.
     */
    public static String llmModel() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_MODEL, "MOCKSERVER_LLM_MODEL", "");
    }

    public static void llmModel(String model) {
        setProperty(MOCKSERVER_LLM_MODEL, model);
    }

    /**
     * Base URL override for the default runtime-LLM backend; empty means the
     * per-provider default applies.
     */
    public static String llmBaseUrl() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_BASE_URL, "MOCKSERVER_LLM_BASE_URL", "");
    }

    public static void llmBaseUrl(String baseUrl) {
        setProperty(MOCKSERVER_LLM_BASE_URL, baseUrl);
    }

    /**
     * Path to a JSON file declaring named runtime-LLM backends (layer 3 of
     * backend resolution). Empty by default.
     */
    public static String llmBackendsConfig() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_BACKENDS_CONFIG, "MOCKSERVER_LLM_BACKENDS_CONFIG", "");
    }

    public static void llmBackendsConfig(String path) {
        setProperty(MOCKSERVER_LLM_BACKENDS_CONFIG, path);
    }

    /**
     * Per-request timeout (milliseconds) for outbound runtime-LLM calls. A
     * backend's own {@code timeoutMillis} overrides this. Default 30000.
     */
    public static long llmRequestTimeoutMillis() {
        return readLongProperty(MOCKSERVER_LLM_REQUEST_TIMEOUT_MILLIS, "MOCKSERVER_LLM_REQUEST_TIMEOUT_MILLIS", 30000L);
    }

    public static void llmRequestTimeoutMillis(long millis) {
        setProperty(MOCKSERVER_LLM_REQUEST_TIMEOUT_MILLIS, "" + millis);
    }

    /**
     * Whether to enable LLM-powered semantic drift analysis. When enabled and a
     * runtime LLM backend is available, each structural drift record is enriched
     * with a severity classification (BREAKING / WARNING / INFORMATIONAL) and an
     * explanation from the LLM. Default false (opt-in).
     */
    public static boolean driftSemanticAnalysisEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(
            PROPERTIES, MOCKSERVER_DRIFT_SEMANTIC_ANALYSIS_ENABLED, "MOCKSERVER_DRIFT_SEMANTIC_ANALYSIS_ENABLED", "false"));
    }

    public static void driftSemanticAnalysisEnabled(boolean enabled) {
        setProperty(MOCKSERVER_DRIFT_SEMANTIC_ANALYSIS_ENABLED, "" + enabled);
    }

    /**
     * p95 response time threshold (in milliseconds) for performance drift detection.
     * When set to a positive value, a PERFORMANCE drift record is emitted whenever
     * the p95 response time for an expectation exceeds this threshold. Default 0
     * (disabled).
     */
    public static long driftResponseTimeThresholdMs() {
        return readLongProperty(MOCKSERVER_DRIFT_RESPONSE_TIME_THRESHOLD_MS, "MOCKSERVER_DRIFT_RESPONSE_TIME_THRESHOLD_MS", 0L);
    }

    public static void driftResponseTimeThresholdMs(long thresholdMs) {
        setProperty(MOCKSERVER_DRIFT_RESPONSE_TIME_THRESHOLD_MS, "" + thresholdMs);
    }

    /**
     * Comma-separated JSON field names whose values are redacted from recorded
     * fixture request/response bodies (in addition to the always-redacted
     * sensitive headers). Empty by default. Used by {@code record_llm_fixtures}.
     */
    public static String fixtureBodyRedactFields() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FIXTURE_BODY_REDACT_FIELDS, "MOCKSERVER_FIXTURE_BODY_REDACT_FIELDS", "");
    }

    public static void fixtureBodyRedactFields(String fields) {
        setProperty(MOCKSERVER_FIXTURE_BODY_REDACT_FIELDS, fields);
    }

    /**
     * When true, loading LLM fixtures in strict VCR mode registers a low-priority
     * catch-all per cassette path so a request that matches no recorded entry
     * fails loudly (HTTP 599) instead of falling through. Default false.
     */
    public static boolean llmVcrStrict() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_VCR_STRICT, "MOCKSERVER_LLM_VCR_STRICT", "" + false));
    }

    public static void llmVcrStrict(boolean strict) {
        setProperty(MOCKSERVER_LLM_VCR_STRICT, "" + strict);
    }

    /**
     * When true, MockServer's explicitly-defined metrics (the same gauges exposed
     * for Prometheus) are also exported via OpenTelemetry OTLP. Off by default.
     * No spans or auto-instrumentation are added — metrics only.
     */
    public static boolean otelMetricsEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_OTEL_METRICS_ENABLED, "MOCKSERVER_OTEL_METRICS_ENABLED", "" + false));
    }

    public static void otelMetricsEnabled(boolean enabled) {
        setProperty(MOCKSERVER_OTEL_METRICS_ENABLED, "" + enabled);
    }

    /**
     * When true, MockServer emits explicit GenAI semantic-convention spans for LLM
     * traffic it serves (one span per completion, carrying provider, model, token
     * usage and finish reason) via OpenTelemetry OTLP. Off by default. These are
     * spans MockServer codes deliberately — no auto-instrumentation is added.
     */
    public static boolean otelTracesEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_OTEL_TRACES_ENABLED, "MOCKSERVER_OTEL_TRACES_ENABLED", "" + false));
    }

    public static void otelTracesEnabled(boolean enabled) {
        setProperty(MOCKSERVER_OTEL_TRACES_ENABLED, "" + enabled);
    }

    /**
     * Base OTLP HTTP endpoint for the collector (e.g. {@code http://localhost:4318}).
     * The {@code /v1/metrics} and {@code /v1/traces} paths are appended per signal.
     * Empty uses the OTLP exporter defaults ({@code http://localhost:4318}). A value
     * that already ends in {@code /v1/metrics} or {@code /v1/traces} is accepted and
     * normalised to the base.
     */
    public static String otelEndpoint() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_OTEL_ENDPOINT, "MOCKSERVER_OTEL_ENDPOINT", "");
    }

    public static void otelEndpoint(String endpoint) {
        setProperty(MOCKSERVER_OTEL_ENDPOINT, endpoint);
    }

    /**
     * How often (seconds) OTel metrics are exported. Default 60.
     */
    public static long otelMetricsExportIntervalSeconds() {
        // clamp to >= 1s; a zero/negative interval would make PeriodicMetricReader throw
        return Math.max(1L, readLongProperty(MOCKSERVER_OTEL_METRICS_EXPORT_INTERVAL_SECONDS, "MOCKSERVER_OTEL_METRICS_EXPORT_INTERVAL_SECONDS", 60L));
    }

    public static void otelMetricsExportIntervalSeconds(long seconds) {
        setProperty(MOCKSERVER_OTEL_METRICS_EXPORT_INTERVAL_SECONDS, "" + seconds);
    }

    /**
     * When true, MockServer copies the incoming W3C {@code traceparent} and
     * {@code tracestate} headers into mock responses. Off by default so
     * responses are not modified unless the user opts in.
     */
    public static boolean otelPropagateTraceContext() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_OTEL_PROPAGATE_TRACE_CONTEXT, "MOCKSERVER_OTEL_PROPAGATE_TRACE_CONTEXT", "" + false));
    }

    public static void otelPropagateTraceContext(boolean enabled) {
        setProperty(MOCKSERVER_OTEL_PROPAGATE_TRACE_CONTEXT, "" + enabled);
    }

    /**
     * When true, MockServer generates a new W3C trace ID for incoming requests
     * that do not carry a {@code traceparent} header. Off by default.
     */
    public static boolean otelGenerateTraceId() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_OTEL_GENERATE_TRACE_ID, "MOCKSERVER_OTEL_GENERATE_TRACE_ID", "" + false));
    }

    public static void otelGenerateTraceId(boolean enabled) {
        setProperty(MOCKSERVER_OTEL_GENERATE_TRACE_ID, "" + enabled);
    }

    /**
     * Opt-in switch for fuzzy, LLM-judged semantic prompt matching (the
     * {@code semanticMatch} conversation predicate). Off by default. Even when
     * on, it only activates if a runtime LLM backend resolves; otherwise the
     * predicate is ignored. Non-deterministic by nature — exploratory only,
     * never for CI assertions. See {@link org.mockserver.llm.semantic.SemanticMatching}.
     */
    public static boolean llmSemanticMatchingEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_SEMANTIC_MATCHING_ENABLED, "MOCKSERVER_LLM_SEMANTIC_MATCHING_ENABLED", "" + false));
    }

    public static void llmSemanticMatchingEnabled(boolean enabled) {
        setProperty(MOCKSERVER_LLM_SEMANTIC_MATCHING_ENABLED, "" + enabled);
    }

    public static long regexMatchingTimeoutMillis() {
        return readLongProperty(MOCKSERVER_REGEX_MATCHING_TIMEOUT_MILLIS, "MOCKSERVER_REGEX_MATCHING_TIMEOUT_MILLIS", 5000L);
    }

    /**
     * Maximum time (in milliseconds) allowed for evaluating a single regular expression
     * during request matching. A pathological pattern that exceeds this budget is treated
     * as a non-match (and a WARN log entry is written) so the server cannot be wedged by
     * exponential regex backtracking from an attacker-controlled expectation or input.
     * <p>
     * The default is 5000 milliseconds. The headroom over typical matching time keeps
     * normal patterns well clear of the cutoff while still bounding pathological
     * backtracking (which takes minutes to hours). Set to 0 or a negative value to
     * disable the timeout.
     *
     * @param milliseconds regex evaluation timeout in milliseconds
     */
    public static void regexMatchingTimeoutMillis(long milliseconds) {
        setProperty(MOCKSERVER_REGEX_MATCHING_TIMEOUT_MILLIS, "" + milliseconds);
    }

    public static long xpathMatchingTimeoutMillis() {
        return readLongProperty(MOCKSERVER_XPATH_MATCHING_TIMEOUT_MILLIS, "MOCKSERVER_XPATH_MATCHING_TIMEOUT_MILLIS", 5000L);
    }

    /**
     * Maximum time (in milliseconds) allowed for evaluating a single XPath expression
     * against an XML document during request matching. Exceeding this budget is treated as
     * a non-match and a WARN log entry is written, protecting MockServer from XPath-based
     * denial-of-service.
     * <p>
     * The default is 5000 milliseconds, well above typical XPath evaluation time, so the
     * timeout only fires on truly pathological expressions or documents. Set to 0 or a
     * negative value to disable the timeout.
     *
     * @param milliseconds XPath evaluation timeout in milliseconds
     */
    public static void xpathMatchingTimeoutMillis(long milliseconds) {
        setProperty(MOCKSERVER_XPATH_MATCHING_TIMEOUT_MILLIS, "" + milliseconds);
    }

    public static String customJsonUnitMatchersClass() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CUSTOM_JSON_UNIT_MATCHERS_CLASS, "MOCKSERVER_CUSTOM_JSON_UNIT_MATCHERS_CLASS", "");
    }

    /**
     * Fully qualified name of a class implementing {@code org.mockserver.matchers.CustomJsonUnitMatcherProvider}.
     * When set, the class is instantiated via its public no-arg constructor and the matchers it
     * returns are registered with the json-unit configuration used for JSON body matching, so
     * expectations can reference them via the {@code ${json-unit.matches:name}} placeholder
     * (e.g. {@code { "price": "${json-unit.matches:largerThan}" }}).
     * <p>
     * Misconfigured providers (class not found, wrong type, constructor failure) are logged at
     * WARN and ignored - JSON body matching falls back to the built-in behaviour. Changing the
     * property at runtime causes the provider to be reloaded on the next match.
     * <p>
     * The default is the empty string (no custom matchers).
     *
     * @param customJsonUnitMatchersClass fully qualified provider class name
     */
    public static void customJsonUnitMatchersClass(String customJsonUnitMatchersClass) {
        setProperty(MOCKSERVER_CUSTOM_JSON_UNIT_MATCHERS_CLASS, customJsonUnitMatchersClass);
    }

    /**
     * If true semicolons are treated as a separator for a query parameter string, if false the semicolon is treated as a normal character that is part of a query parameter value.
     * <p>
     * The default is true
     *
     * @param useAsQueryParameterSeparator true semicolons are treated as a separator for a query parameter string
     */
    public static void useSemicolonAsQueryParameterSeparator(boolean useAsQueryParameterSeparator) {
        setProperty(MOCKSERVER_USE_SEMICOLON_AS_QUERY_PARAMETER_SEPARATOR, "" + useAsQueryParameterSeparator);
    }

    public static boolean useSemicolonAsQueryParameterSeparator() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_USE_SEMICOLON_AS_QUERY_PARAMETER_SEPARATOR, "MOCKSERVER_USE_SEMICOLON_AS_QUERY_PARAMETER_SEPARATOR", "true"));
    }

    /**
     * If true requests are assumed as binary if the method isn't one of "GET", "POST", "PUT", "HEAD", "OPTIONS", "PATCH", "DELETE", "TRACE" or "CONNECT"
     * <p>
     * The default is true
     *
     * @param assumeAllRequestsAreHttp if true requests are assumed as binary if the method isn't one of "GET", "POST", "PUT", "HEAD", "OPTIONS", "PATCH", "DELETE", "TRACE" or "CONNECT"
     */
    public static void assumeAllRequestsAreHttp(boolean assumeAllRequestsAreHttp) {
        setProperty(MOCKSERVER_ASSUME_ALL_REQUESTS_ARE_HTTP, "" + assumeAllRequestsAreHttp);
    }

    public static boolean assumeAllRequestsAreHttp() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_ASSUME_ALL_REQUESTS_ARE_HTTP, "MOCKSERVER_ASSUME_ALL_REQUESTS_ARE_HTTP", "false"));
    }

    /**
     * If false HTTP/2 is disabled and ALPN no longer advertises h2, so HTTP/2 capable clients are
     * forced to use HTTP/1.1 (and the HTTP/2 cleartext h2c upgrade is not detected)
     * <p>
     * The default is true
     *
     * @param http2Enabled if false HTTP/2 is disabled and clients are forced to use HTTP/1.1
     */
    public static void http2Enabled(boolean http2Enabled) {
        setProperty(MOCKSERVER_HTTP2_ENABLED, "" + http2Enabled);
    }

    public static boolean http2Enabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_HTTP2_ENABLED, "MOCKSERVER_HTTP2_ENABLED", "true"));
    }

    /**
     * If true the BinaryRequestProxyingHandler.binaryExchangeCallback is called before a response is received from the
     * remote host. This enables the proxying of messages without a response.
     * <p>
     * The default is false
     *
     * @param forwardBinaryRequestsAsynchronously target value
     */
    public static void forwardBinaryRequestsWithoutWaitingForResponse(boolean forwardBinaryRequestsAsynchronously) {
        setProperty(MOCKSERVER_FORWARD_BINARY_REQUESTS_WITHOUT_WAITING_FOR_RESPONSE, "" + forwardBinaryRequestsAsynchronously);
    }

    public static boolean forwardBinaryRequestsWithoutWaitingForResponse() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_BINARY_REQUESTS_WITHOUT_WAITING_FOR_RESPONSE, "MOCKSERVER_FORWARD_BINARY_REQUESTS_WITHOUT_WAITING_FOR_RESPONSE", "false"));
    }

    // CORS

    public static boolean enableCORSForAPI() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_ENABLE_CORS_FOR_API, "MOCKSERVER_ENABLE_CORS_FOR_API", "false"));
    }

    /**
     * Enable CORS for MockServer REST API so that the API can be used for javascript running in browsers, such as selenium
     * <p>
     * The default is false
     *
     * @param enable CORS for MockServer REST API
     */
    public static void enableCORSForAPI(boolean enable) {
        setProperty(MOCKSERVER_ENABLE_CORS_FOR_API, "" + enable);
    }

    public static boolean enableCORSForAllResponses() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_ENABLE_CORS_FOR_ALL_RESPONSES, "MOCKSERVER_ENABLE_CORS_FOR_ALL_RESPONSES", "false"));
    }

    /**
     * Enable CORS for all responses from MockServer, including the REST API and expectation responses
     * <p>
     * The default is false
     *
     * @param enable CORS for all responses from MockServer
     */
    public static void enableCORSForAllResponses(boolean enable) {
        setProperty(MOCKSERVER_ENABLE_CORS_FOR_ALL_RESPONSES, "" + enable);
    }

    public static String corsAllowOrigin() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CORS_ALLOW_ORIGIN, "MOCKSERVER_CORS_ALLOW_ORIGIN", "");
    }

    /**
     * <p>the value used for CORS in the access-control-allow-origin header.</p>
     * <p>The default is ""</p>
     *
     * @param corsAllowOrigin the value used for CORS in the access-control-allow-methods header
     */
    public static void corsAllowOrigin(String corsAllowOrigin) {
        setProperty(MOCKSERVER_CORS_ALLOW_ORIGIN, corsAllowOrigin);
    }

    public static String corsAllowMethods() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CORS_ALLOW_METHODS, "MOCKSERVER_CORS_ALLOW_METHODS", "");
    }

    /**
     * <p>The value used for CORS in the access-control-allow-methods header.</p>
     * <p>The property default is blank; when blank, MockServer applies "CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, PATCH, TRACE" as a built-in fallback (see CORSHeaders).</p>
     *
     * @param corsAllowMethods the value used for CORS in the access-control-allow-methods header
     */
    public static void corsAllowMethods(String corsAllowMethods) {
        setProperty(MOCKSERVER_CORS_ALLOW_METHODS, corsAllowMethods);
    }

    public static String corsAllowHeaders() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CORS_ALLOW_HEADERS, "MOCKSERVER_CORS_ALLOW_HEADERS", "");
    }

    /**
     * <p>the value used for CORS in the access-control-allow-headers and access-control-expose-headers headers.</p>
     * <p>In addition to this default value any headers specified in the request header access-control-request-headers also get added to access-control-allow-headers and access-control-expose-headers headers in a CORS response.</p>
     * <p>The property default is blank; when blank, MockServer applies "Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization" as a built-in fallback (see CORSHeaders).</p>
     *
     * @param corsAllowHeaders the value used for CORS in the access-control-allow-headers and access-control-expose-headers headers
     */
    public static void corsAllowHeaders(String corsAllowHeaders) {
        setProperty(MOCKSERVER_CORS_ALLOW_HEADERS, corsAllowHeaders);
    }

    public static boolean corsAllowCredentials() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CORS_ALLOW_CREDENTIALS, "MOCKSERVER_CORS_ALLOW_CREDENTIALS", "false"));
    }

    /**
     * The value used for CORS in the access-control-allow-credentials header.
     * <p>
     * The default is false
     *
     * @param allow the value used for CORS in the access-control-allow-credentials header
     */
    public static void corsAllowCredentials(boolean allow) {
        setProperty(MOCKSERVER_CORS_ALLOW_CREDENTIALS, "" + allow);
    }

    public static int corsMaxAgeInSeconds() {
        return readIntegerProperty(MOCKSERVER_CORS_MAX_AGE_IN_SECONDS, "MOCKSERVER_CORS_MAX_AGE_IN_SECONDS", 0);
    }

    /**
     * The value used for CORS in the access-control-max-age header.
     * <p>
     * The default is 0
     *
     * @param ageInSeconds the value used for CORS in the access-control-max-age header.
     */
    public static void corsMaxAgeInSeconds(int ageInSeconds) {
        setProperty(MOCKSERVER_CORS_MAX_AGE_IN_SECONDS, "" + ageInSeconds);
    }

    // template restrictions

    public static String javascriptDisallowedClasses() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_JAVASCRIPT_DISALLOWED_CLASSES, "MOCKSERVER_JAVASCRIPT_DISALLOWED_CLASSES", "");
    }

    /**
     * Set comma separate list of classes not allowed to be used by javascript templates
     * <p>
     * The default is all allowed
     *
     * @param javascriptDisallowedClasses comma separated list of classes not allowed to be used
     */
    public static void javascriptDisallowedClasses(String javascriptDisallowedClasses) {
        setProperty(MOCKSERVER_JAVASCRIPT_DISALLOWED_CLASSES, javascriptDisallowedClasses);
    }

    public static String javascriptDisallowedText() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_JAVASCRIPT_DISALLOWED_TEXT, "MOCKSERVER_JAVASCRIPT_DISALLOWED_TEXT", "");
    }

    /**
     * Set comma separate list of text not allowed to be contained in javascript templates
     * <p>
     * The default is all allowed
     *
     * @param javascriptDisallowedText comma separated list of text not allowed to be contained in javascript templates
     */
    public static void javascriptDisallowedText(String javascriptDisallowedText) {
        setProperty(MOCKSERVER_JAVASCRIPT_DISALLOWED_TEXT, javascriptDisallowedText);
    }


    public static boolean velocityDisallowClassLoading() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_VELOCITY_DISALLOW_CLASS_LOADING, "MOCKSERVER_VELOCITY_DISALLOW_CLASS_LOADING", "" + false));
    }

    /**
     * If true class loading is not allowed in velocity templates
     * <p>
     * The default is false
     *
     * @param velocityDisallowClassLoading class loading is not allowed in velocity templates
     */
    public static void velocityDisallowClassLoading(boolean velocityDisallowClassLoading) {
        setProperty(MOCKSERVER_VELOCITY_DISALLOW_CLASS_LOADING, "" + velocityDisallowClassLoading);
    }

    public static String velocityDisallowedText() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_VELOCITY_DISALLOWED_TEXT, "MOCKSERVER_VELOCITY_DISALLOWED_TEXT", "");
    }

    /**
     * Set comma separate list of text not allowed to be contained in velocity templates
     * <p>
     * The default is all allowed
     *
     * @param velocityDisallowedText comma separated list of text not allowed to be contained in velocity templates
     */
    public static void velocityDisallowedText(String velocityDisallowedText) {
        setProperty(MOCKSERVER_VELOCITY_DISALLOWED_TEXT, velocityDisallowedText);
    }

    public static String mustacheDisallowedText() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_MUSTACHE_DISALLOWED_TEXT, "MOCKSERVER_MUSTACHE_DISALLOWED_TEXT", "");
    }

    /**
     * Set comma separate list of text not allowed to be contained in mustache templates
     * <p>
     * The default is all allowed
     *
     * @param mustacheDisallowedText comma separated list of text not allowed to be contained in mustache templates
     */
    public static void mustacheDisallowedText(String mustacheDisallowedText) {
        setProperty(MOCKSERVER_MUSTACHE_DISALLOWED_TEXT, mustacheDisallowedText);
    }

    // mock initialization

    public static String initializationClass() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_INITIALIZATION_CLASS, "MOCKSERVER_INITIALIZATION_CLASS", "");
    }

    /**
     * The class (and package) used to initialize expectations in MockServer at startup, if set MockServer will load and call this class to initialise expectations when is starts.
     * <p>
     * The default is null
     *
     * @param initializationClass class (and package) used to initialize expectations in MockServer at startup
     */
    public static void initializationClass(String initializationClass) {
        setProperty(MOCKSERVER_INITIALIZATION_CLASS, initializationClass);
    }

    public static String initializationJsonPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_INITIALIZATION_JSON_PATH, "MOCKSERVER_INITIALIZATION_JSON_PATH", "");
    }

    /**
     * <p>The path to the json file used to initialize expectations in MockServer at startup, if set MockServer will load this file and initialise expectations for each item in the file when is starts.</p>
     * <p>The expected format of the file is a JSON array of expectations, as per the <a target="_blank" href="https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.15.x#/Expectations" target="_blank">REST API format</a></p>
     * <p>To watch multiple files use a file globs as documented here: https://mock-server.com/mock_server/initializing_expectations.html#expectation_initializer_json_glob_patterns</p>
     *
     * @param initializationJsonPath path to the json file used to initialize expectations in MockServer at startup
     */
    public static void initializationJsonPath(String initializationJsonPath) {
        setProperty(MOCKSERVER_INITIALIZATION_JSON_PATH, initializationJsonPath);
    }

    public static String initializationOpenAPIPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_INITIALIZATION_OPENAPI_PATH, "MOCKSERVER_INITIALIZATION_OPENAPI_PATH", "");
    }

    /**
     * <p>The path to the OpenAPI spec file used to initialize expectations in MockServer at startup, if set MockServer will load this file and create expectations for each operation when it starts.</p>
     * <p>The file can be a YAML (.yaml, .yml) or JSON (.json) OpenAPI v3 specification.</p>
     * <p>To watch multiple files use file globs as documented here: https://mock-server.com/mock_server/initializing_expectations.html#expectation_initializer_json_glob_patterns</p>
     *
     * @param initializationOpenAPIPath path to the OpenAPI spec file used to initialize expectations in MockServer at startup
     */
    public static void initializationOpenAPIPath(String initializationOpenAPIPath) {
        setProperty(MOCKSERVER_INITIALIZATION_OPENAPI_PATH, initializationOpenAPIPath);
    }

    public static String openAPIContextPathPrefix() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_OPENAPI_CONTEXT_PATH_PREFIX, "MOCKSERVER_OPENAPI_CONTEXT_PATH_PREFIX", "");
    }

    /**
     * <p>A path prefix to add to all paths generated from OpenAPI specifications.</p>
     * <p>For example, if set to "/api/v1" then a path "/pets" from the spec becomes "/api/v1/pets".</p>
     *
     * @param openAPIContextPathPrefix the path prefix to add to OpenAPI paths
     */
    public static void openAPIContextPathPrefix(String openAPIContextPathPrefix) {
        setProperty(MOCKSERVER_OPENAPI_CONTEXT_PATH_PREFIX, openAPIContextPathPrefix);
    }

    public static boolean openAPIResponseValidation() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_OPENAPI_RESPONSE_VALIDATION, "MOCKSERVER_OPENAPI_RESPONSE_VALIDATION", "" + false));
    }

    /**
     * <p>If enabled MockServer will validate that mock responses conform to the OpenAPI spec schema they were generated from.</p>
     * <p>Validation is advisory only - responses are still returned to the client even if validation fails.</p>
     *
     * <p>The default is false</p>
     *
     * @param enable if enabled mock responses will be validated against the OpenAPI spec schema
     */
    public static void openAPIResponseValidation(boolean enable) {
        setProperty(MOCKSERVER_OPENAPI_RESPONSE_VALIDATION, "" + enable);
    }

    public static boolean watchInitializationJson() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_WATCH_INITIALIZATION_JSON, "MOCKSERVER_WATCH_INITIALIZATION_JSON", "" + false));
    }

    /**
     * <p>If enabled the initialization json file will be watched for changes, any changes found will result in expectations being created, remove or updated by matching against their key.</p>
     * <p>If duplicate keys exist only the last duplicate key in the file will be processed and all duplicates except the last duplicate will be removed.</p>
     * <p>The order of expectations in the file is the order in which they are created if they are new, however, re-ordering existing expectations does not change the order they are matched against incoming requests.</p>
     *
     * <p>The default is false</p>
     *
     * @param enable if enabled the initialization json file will be watched for changes
     */
    public static void watchInitializationJson(boolean enable) {
        setProperty(MOCKSERVER_WATCH_INITIALIZATION_JSON, "" + enable);
    }

    // mock persistence

    public static boolean persistExpectations() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_PERSIST_EXPECTATIONS, "MOCKSERVER_PERSIST_EXPECTATIONS", "" + false));
    }

    /**
     * Enable the persisting of expectations as json, which is updated whenever the expectation state is updated (i.e. add, clear, expires, etc)
     * <p>
     * The default is false
     *
     * @param enable the persisting of expectations as json
     */
    public static void persistExpectations(boolean enable) {
        setProperty(MOCKSERVER_PERSIST_EXPECTATIONS, "" + enable);
    }

    public static String persistedExpectationsPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PERSISTED_EXPECTATIONS_PATH, "MOCKSERVER_PERSISTED_EXPECTATIONS_PATH", "persistedExpectations.json");
    }

    /**
     * The file path used to save persisted expectations as json, which is updated whenever the expectation state is updated (i.e. add, clear, expires, etc)
     * <p>
     * The default is "persistedExpectations.json"
     *
     * @param persistedExpectationsPath file path used to save persisted expectations as json
     */
    public static void persistedExpectationsPath(String persistedExpectationsPath) {
        setProperty(MOCKSERVER_PERSISTED_EXPECTATIONS_PATH, persistedExpectationsPath);
    }

    // recorded expectation persistence

    public static boolean persistRecordedExpectations() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_PERSIST_RECORDED_EXPECTATIONS, "MOCKSERVER_PERSIST_RECORDED_EXPECTATIONS", "" + false));
    }

    /**
     * Enable the persisting of recorded expectations (proxy traffic) as json, which is updated whenever a new request is forwarded
     * <p>
     * The default is false
     *
     * @param enable the persisting of recorded expectations as json
     */
    public static void persistRecordedExpectations(boolean enable) {
        setProperty(MOCKSERVER_PERSIST_RECORDED_EXPECTATIONS, "" + enable);
    }

    public static String persistedRecordedExpectationsPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PERSISTED_RECORDED_EXPECTATIONS_PATH, "MOCKSERVER_PERSISTED_RECORDED_EXPECTATIONS_PATH", "persistedRecordedExpectations.json");
    }

    /**
     * The file path used to save persisted recorded expectations as json, which is updated whenever a new request is forwarded
     * <p>
     * The default is "persistedRecordedExpectations.json"
     *
     * @param persistedRecordedExpectationsPath file path used to save persisted recorded expectations as json
     */
    public static void persistedRecordedExpectationsPath(String persistedRecordedExpectationsPath) {
        setProperty(MOCKSERVER_PERSISTED_RECORDED_EXPECTATIONS_PATH, persistedRecordedExpectationsPath);
    }

    // state backend (G10 phase 2a)

    /**
     * Returns the state backend type. Currently only "memory" is supported
     * (default). Phase 2b will add "infinispan" for clustered state.
     */
    public static String stateBackend() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_STATE_BACKEND, "MOCKSERVER_STATE_BACKEND", "memory");
    }

    /**
     * Sets the state backend type. Currently only "memory" is supported.
     *
     * @param stateBackend the backend type (e.g. "memory")
     */
    public static void stateBackend(String stateBackend) {
        setProperty(MOCKSERVER_STATE_BACKEND, stateBackend);
    }

    /**
     * Returns the blob store type. "filesystem" (default) delegates to the
     * existing file persistence paths so on-disk behaviour is unchanged;
     * "memory" keeps blobs in-memory only (lost on process exit).
     */
    public static String blobStoreType() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_TYPE, "MOCKSERVER_BLOB_STORE_TYPE", "filesystem");
    }

    /**
     * Sets the blob store type.
     *
     * @param blobStoreType the blob store type (e.g. "memory", "filesystem")
     */
    public static void blobStoreType(String blobStoreType) {
        setProperty(MOCKSERVER_BLOB_STORE_TYPE, blobStoreType);
    }

    // cloud blob store configuration

    /**
     * Returns the cloud blob store bucket name (S3 or GCS bucket).
     */
    public static String blobStoreBucket() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_BUCKET, "MOCKSERVER_BLOB_STORE_BUCKET", "");
    }

    public static void blobStoreBucket(String blobStoreBucket) {
        setProperty(MOCKSERVER_BLOB_STORE_BUCKET, blobStoreBucket);
    }

    /**
     * Returns the cloud blob store region (e.g. "us-east-1" for S3).
     */
    public static String blobStoreRegion() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_REGION, "MOCKSERVER_BLOB_STORE_REGION", "");
    }

    public static void blobStoreRegion(String blobStoreRegion) {
        setProperty(MOCKSERVER_BLOB_STORE_REGION, blobStoreRegion);
    }

    /**
     * Returns the cloud blob store endpoint override URL.
     */
    public static String blobStoreEndpoint() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_ENDPOINT, "MOCKSERVER_BLOB_STORE_ENDPOINT", "");
    }

    public static void blobStoreEndpoint(String blobStoreEndpoint) {
        setProperty(MOCKSERVER_BLOB_STORE_ENDPOINT, blobStoreEndpoint);
    }

    /**
     * Returns the key prefix for cloud blob store objects.
     */
    public static String blobStoreKeyPrefix() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_KEY_PREFIX, "MOCKSERVER_BLOB_STORE_KEY_PREFIX", "");
    }

    public static void blobStoreKeyPrefix(String blobStoreKeyPrefix) {
        setProperty(MOCKSERVER_BLOB_STORE_KEY_PREFIX, blobStoreKeyPrefix);
    }

    /**
     * Returns the explicit access key ID for cloud blob store authentication.
     */
    public static String blobStoreAccessKeyId() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_ACCESS_KEY_ID, "MOCKSERVER_BLOB_STORE_ACCESS_KEY_ID", "");
    }

    public static void blobStoreAccessKeyId(String blobStoreAccessKeyId) {
        setProperty(MOCKSERVER_BLOB_STORE_ACCESS_KEY_ID, blobStoreAccessKeyId);
    }

    /**
     * Returns the explicit secret access key for cloud blob store authentication.
     */
    public static String blobStoreSecretAccessKey() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_SECRET_ACCESS_KEY, "MOCKSERVER_BLOB_STORE_SECRET_ACCESS_KEY", "");
    }

    public static void blobStoreSecretAccessKey(String blobStoreSecretAccessKey) {
        setProperty(MOCKSERVER_BLOB_STORE_SECRET_ACCESS_KEY, blobStoreSecretAccessKey);
    }

    /**
     * Returns the Azure Blob Storage container name.
     */
    public static String blobStoreContainer() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_CONTAINER, "MOCKSERVER_BLOB_STORE_CONTAINER", "");
    }

    public static void blobStoreContainer(String blobStoreContainer) {
        setProperty(MOCKSERVER_BLOB_STORE_CONTAINER, blobStoreContainer);
    }

    /**
     * Returns the Azure Blob Storage connection string.
     */
    public static String blobStoreConnectionString() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_CONNECTION_STRING, "MOCKSERVER_BLOB_STORE_CONNECTION_STRING", "");
    }

    public static void blobStoreConnectionString(String blobStoreConnectionString) {
        setProperty(MOCKSERVER_BLOB_STORE_CONNECTION_STRING, blobStoreConnectionString);
    }

    /**
     * Returns the GCS project ID.
     */
    public static String blobStoreProjectId() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_PROJECT_ID, "MOCKSERVER_BLOB_STORE_PROJECT_ID", "");
    }

    public static void blobStoreProjectId(String blobStoreProjectId) {
        setProperty(MOCKSERVER_BLOB_STORE_PROJECT_ID, blobStoreProjectId);
    }

    // --- clustering (G10 phase 2c) ---

    /**
     * Returns whether clustering is enabled. Default is {@code false}.
     */
    public static boolean clusterEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CLUSTER_ENABLED, "MOCKSERVER_CLUSTER_ENABLED", "false"));
    }

    /**
     * Enables or disables clustering.
     *
     * @param clusterEnabled true to enable JGroups transport
     */
    public static void clusterEnabled(boolean clusterEnabled) {
        setProperty(MOCKSERVER_CLUSTER_ENABLED, String.valueOf(clusterEnabled));
    }

    /**
     * Returns the JGroups cluster name. Default is {@code "mockserver-cluster"}.
     */
    public static String clusterName() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CLUSTER_NAME, "MOCKSERVER_CLUSTER_NAME", "mockserver-cluster");
    }

    /**
     * Sets the JGroups cluster name.
     *
     * @param clusterName the cluster identifier
     */
    public static void clusterName(String clusterName) {
        setProperty(MOCKSERVER_CLUSTER_NAME, clusterName);
    }

    /**
     * Returns the optional path to a JGroups XML transport configuration.
     * Default is empty string (use the built-in embedded stack). Empty
     * string is used instead of {@code null} because the property cache
     * is a {@code ConcurrentHashMap} which does not permit null values.
     */
    public static String clusterTransportConfig() {
        String value = readPropertyHierarchically(PROPERTIES, MOCKSERVER_CLUSTER_TRANSPORT_CONFIG, "MOCKSERVER_CLUSTER_TRANSPORT_CONFIG", "");
        return value != null && !value.isEmpty() ? value : null;
    }

    /**
     * Sets the path to a custom JGroups XML transport configuration.
     *
     * @param clusterTransportConfig path to JGroups XML, or null for default
     */
    public static void clusterTransportConfig(String clusterTransportConfig) {
        // Guard against null: System.setProperty (called by setProperty)
        // throws NPE for null values. Store empty string to mirror other
        // nullable string properties.
        setProperty(MOCKSERVER_CLUSTER_TRANSPORT_CONFIG, clusterTransportConfig != null ? clusterTransportConfig : "");
    }

    // verification

    public static Integer maximumNumberOfRequestToReturnInVerificationFailure() {
        return readIntegerProperty(MOCKSERVER_MAXIMUM_NUMBER_OF_REQUESTS_TO_RETURN_IN_VERIFICATION_FAILURE, "MOCKSERVER_MAXIMUM_NUMBER_OF_REQUESTS_TO_RETURN_IN_VERIFICATION_FAILURE", 10);
    }

    /**
     * The maximum number of requests to return in verification failure result, if more expectations are found the failure result does not list them separately
     *
     * @param maximumNumberOfRequestToReturnInVerification maximum number of expectations to return in verification failure result
     */
    public static void maximumNumberOfRequestToReturnInVerificationFailure(Integer maximumNumberOfRequestToReturnInVerification) {
        setProperty(MOCKSERVER_MAXIMUM_NUMBER_OF_REQUESTS_TO_RETURN_IN_VERIFICATION_FAILURE, "" + maximumNumberOfRequestToReturnInVerification);
    }

    public static boolean detailedVerificationFailures() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DETAILED_VERIFICATION_FAILURES, "MOCKSERVER_DETAILED_VERIFICATION_FAILURES", "" + true));
    }

    /**
     * If true (the default) verification failure messages include a detailed diff showing which fields did not match for the closest matching request.
     *
     * @param enable enabled detailed verification failure messages
     */
    public static void detailedVerificationFailures(boolean enable) {
        setProperty(MOCKSERVER_DETAILED_VERIFICATION_FAILURES, "" + enable);
    }

    // proxy

    public static boolean attemptToProxyIfNoMatchingExpectation() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_ATTEMPT_TO_PROXY_IF_NO_MATCHING_EXPECTATION, "MOCKSERVER_ATTEMPT_TO_PROXY_IF_NO_MATCHING_EXPECTATION", "" + true));
    }

    /**
     * If true (the default) when no matching expectation is found, and the host header of the request does not match MockServer's host, then MockServer attempts to proxy the request if that fails then a 404 is returned.
     * If false when no matching expectation is found, and MockServer is not being used as a proxy, then MockServer always returns a 404 immediately.
     *
     * @param enable enables automatically attempted proxying of request that don't match an expectation and look like they should be proxied
     */
    public static void attemptToProxyIfNoMatchingExpectation(boolean enable) {
        setProperty(MOCKSERVER_ATTEMPT_TO_PROXY_IF_NO_MATCHING_EXPECTATION, "" + enable);
    }

    public static InetSocketAddress forwardHttpProxy() {
        return readInetSocketAddressProperty(MOCKSERVER_FORWARD_HTTP_PROXY, "MOCKSERVER_FORWARD_HTTP_PROXY");
    }

    /**
     * Use HTTP proxy (i.e. via Host header) for all outbound / forwarded requests
     * <p>
     * The default is null
     *
     * @param hostAndPort host and port for HTTP proxy (i.e. via Host header) for all outbound / forwarded requests
     */
    public static void forwardHttpProxy(String hostAndPort) {
        validateHostAndPortAndSetProperty(hostAndPort, MOCKSERVER_FORWARD_HTTP_PROXY);
    }

    /**
     * Use HTTP proxy (i.e. via Host header) for all outbound / forwarded requests
     * <p>
     * The default is null
     *
     * @param hostAndPort host and port for HTTP proxy (i.e. via Host header) for all outbound / forwarded requests
     */
    public static void forwardHttpProxy(InetSocketAddress hostAndPort) {
        validateHostAndPortAndSetProperty(hostAndPort.toString(), MOCKSERVER_FORWARD_HTTP_PROXY);
    }

    public static InetSocketAddress forwardHttpsProxy() {
        return readInetSocketAddressProperty(MOCKSERVER_FORWARD_HTTPS_PROXY, "MOCKSERVER_FORWARD_HTTPS_PROXY");
    }

    /**
     * Use HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests, supports TLS tunnelling of HTTPS requests
     * <p>
     * The default is null
     *
     * @param hostAndPort host and port for HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests
     */
    public static void forwardHttpsProxy(String hostAndPort) {
        validateHostAndPortAndSetProperty(hostAndPort, MOCKSERVER_FORWARD_HTTPS_PROXY);
    }

    /**
     * Use HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests, supports TLS tunnelling of HTTPS requests
     * <p>
     * The default is null
     *
     * @param hostAndPort host and port for HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests
     */
    public static void forwardHttpsProxy(InetSocketAddress hostAndPort) {
        validateHostAndPortAndSetProperty(hostAndPort.toString(), MOCKSERVER_FORWARD_HTTPS_PROXY);
    }

    public static InetSocketAddress forwardSocksProxy() {
        return readInetSocketAddressProperty(MOCKSERVER_FORWARD_SOCKS_PROXY, "MOCKSERVER_FORWARD_SOCKS_PROXY");
    }

    /**
     * Use SOCKS proxy for all outbound / forwarded requests, support TLS tunnelling of TCP connections
     * <p>
     * The default is null
     *
     * @param hostAndPort host and port for SOCKS proxy for all outbound / forwarded requests
     */
    public static void forwardSocksProxy(String hostAndPort) {
        validateHostAndPortAndSetProperty(hostAndPort, MOCKSERVER_FORWARD_SOCKS_PROXY);
    }

    /**
     * Use SOCKS proxy for all outbound / forwarded requests, support TLS tunnelling of TCP connections
     * <p>
     * The default is null
     *
     * @param hostAndPort host and port for SOCKS proxy for all outbound / forwarded requests
     */
    public static void forwardSocksProxy(InetSocketAddress hostAndPort) {
        validateHostAndPortAndSetProperty(hostAndPort.toString(), MOCKSERVER_FORWARD_SOCKS_PROXY);
    }

    public static String forwardProxyAuthenticationUsername() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_USERNAME, "MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_USERNAME", "");
    }

    /**
     * <p>Username for proxy authentication when using HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests</p>
     * <p><strong>Note:</strong> <a target="_blank" href="https://www.oracle.com/java/technologies/javase/8u111-relnotes.html">8u111 Update Release Notes</a> state that the Basic authentication scheme has been deactivated when setting up an HTTPS tunnel.  To resolve this clear or set to an empty string the following system properties: <code class="inline code">jdk.http.auth.tunneling.disabledSchemes</code> and <code class="inline code">jdk.http.auth.proxying.disabledSchemes</code>.</p>
     * <p>
     * The default is null
     *
     * @param forwardProxyAuthenticationUsername username for proxy authentication
     */
    public static void forwardProxyAuthenticationUsername(String forwardProxyAuthenticationUsername) {
        if (forwardProxyAuthenticationUsername != null) {
            setProperty(MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_USERNAME, forwardProxyAuthenticationUsername);
        } else {
            clearProperty(MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_USERNAME);
        }
    }

    public static String forwardProxyAuthenticationPassword() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_PASSWORD, "MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_PASSWORD", "");
    }

    /**
     * <p>Password for proxy authentication when using HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests</p>
     * <p><strong>Note:</strong> <a target="_blank" href="https://www.oracle.com/java/technologies/javase/8u111-relnotes.html">8u111 Update Release Notes</a> state that the Basic authentication scheme has been deactivated when setting up an HTTPS tunnel.  To resolve this clear or set to an empty string the following system properties: <code class="inline code">jdk.http.auth.tunneling.disabledSchemes</code> and <code class="inline code">jdk.http.auth.proxying.disabledSchemes</code>.</p>
     * <p>
     * The default is null
     *
     * @param forwardProxyAuthenticationPassword password for proxy authentication
     */
    public static void forwardProxyAuthenticationPassword(String forwardProxyAuthenticationPassword) {
        if (forwardProxyAuthenticationPassword != null) {
            setProperty(MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_PASSWORD, forwardProxyAuthenticationPassword);
        } else {
            clearProperty(MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_PASSWORD);
        }
    }

    public static String proxyAuthenticationRealm() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_SERVER_REALM, "MOCKSERVER_PROXY_SERVER_REALM", "MockServer HTTP Proxy");
    }

    /**
     * The authentication realm for proxy authentication to MockServer
     *
     * @param proxyAuthenticationRealm the authentication realm for proxy authentication
     */
    public static void proxyAuthenticationRealm(String proxyAuthenticationRealm) {
        setProperty(MOCKSERVER_PROXY_SERVER_REALM, proxyAuthenticationRealm);
    }

    public static String proxyAuthenticationUsername() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_AUTHENTICATION_USERNAME, "MOCKSERVER_PROXY_AUTHENTICATION_USERNAME", "");
    }

    /**
     * <p>The required username for proxy authentication to MockServer</p>
     * <p><strong>Note:</strong> <a target="_blank" href="https://www.oracle.com/java/technologies/javase/8u111-relnotes.html">8u111 Update Release Notes</a> state that the Basic authentication scheme has been deactivated when setting up an HTTPS tunnel.  To resolve this clear or set to an empty string the following system properties: <code class="inline code">jdk.http.auth.tunneling.disabledSchemes</code> and <code class="inline code">jdk.http.auth.proxying.disabledSchemes</code>.</p>
     * <p>
     * The default is ""
     *
     * @param proxyAuthenticationUsername required username for proxy authentication to MockServer
     */
    public static void proxyAuthenticationUsername(String proxyAuthenticationUsername) {
        setProperty(MOCKSERVER_PROXY_AUTHENTICATION_USERNAME, proxyAuthenticationUsername);
    }

    public static String proxyAuthenticationPassword() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_AUTHENTICATION_PASSWORD, "MOCKSERVER_PROXY_AUTHENTICATION_PASSWORD", "");
    }

    /**
     * <p>The list of hostnames to not use the configured proxy. Several values may be present, seperated by comma (,)</p>
     * The default is ""
     *
     * @param noProxyHosts Comma-seperated list of hosts to not be proxied.
     */
    public static void noProxyHosts(String noProxyHosts) {
        setProperty(MOCKSERVER_NO_PROXY_HOSTS, noProxyHosts);
    }

    public static String noProxyHosts() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_NO_PROXY_HOSTS, "MOCKSERVER_NO_PROXY_HOSTS", "");
    }

    public static String proxyRemoteHost() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_REMOTE_HOST, "MOCKSERVER_PROXY_REMOTE_HOST", "");
    }

    public static void proxyRemoteHost(String proxyRemoteHost) {
        setProperty(MOCKSERVER_PROXY_REMOTE_HOST, proxyRemoteHost);
    }

    public static Integer proxyRemotePort() {
        String value = readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_REMOTE_PORT, "MOCKSERVER_PROXY_REMOTE_PORT", "");
        if (isBlank(value)) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("proxyRemotePort must be an integer between 1 and 65535, got: " + value);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("proxyRemotePort must be between 1 and 65535, got: " + port);
        }
        return port;
    }

    public static void proxyRemotePort(Integer proxyRemotePort) {
        setProperty(MOCKSERVER_PROXY_REMOTE_PORT, proxyRemotePort != null ? "" + proxyRemotePort : "");
    }

    public static boolean forwardAdjustHostHeader() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_ADJUST_HOST_HEADER, "MOCKSERVER_FORWARD_ADJUST_HOST_HEADER", "" + true));
    }

    /**
     * If true (the default) the Host header will be automatically adjusted to match the target server when forwarding requests.
     * This prevents HTTP 421 Misdirected Request errors when the target server validates Host headers.
     * If false the original Host header is preserved.
     *
     * @param enable enables automatic Host header adjustment for forwarded requests
     */
    public static void forwardAdjustHostHeader(boolean enable) {
        setProperty(MOCKSERVER_FORWARD_ADJUST_HOST_HEADER, "" + enable);
    }

    public static String forwardDefaultHostHeader() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_DEFAULT_HOST_HEADER, "MOCKSERVER_FORWARD_DEFAULT_HOST_HEADER", "");
    }

    public static void forwardDefaultHostHeader(String hostHeader) {
        setProperty(MOCKSERVER_FORWARD_DEFAULT_HOST_HEADER, hostHeader);
    }

    @SuppressWarnings("unchecked")
    public static List<ProxyPassMapping> proxyPass() {
        String value = readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_PASS, "MOCKSERVER_PROXY_PASS", "");
        if (isBlank(value)) {
            return Collections.emptyList();
        }
        try {
            return ObjectMapperFactory.createObjectMapper().readValue(value, new TypeReference<List<ProxyPassMapping>>() {});
        } catch (Exception e) {
            if (LoggerHolder.LOGGER != null) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("invalid proxyPass value: " + value)
                        .setThrowable(e)
                );
            }
            return Collections.emptyList();
        }
    }

    /**
     * Configure ProxyPass mappings that map incoming path prefixes to upstream servers with automatic path rewriting.
     * Value is a JSON array of objects with pathPrefix, targetUri, and optional preserveHost fields.
     *
     * @param proxyPassJson JSON array string, e.g. [{"pathPrefix":"/api/","targetUri":"https://backend:8443/services/"}]
     */
    public static void proxyPass(String proxyPassJson) {
        setProperty(MOCKSERVER_PROXY_PASS, proxyPassJson);
    }

    /**
     * Configure ProxyPass mappings that map incoming path prefixes to upstream servers with automatic path rewriting.
     *
     * @param mappings list of ProxyPassMapping objects
     */
    public static void proxyPass(List<ProxyPassMapping> mappings) {
        try {
            setProperty(MOCKSERVER_PROXY_PASS, ObjectMapperFactory.createObjectMapper().writeValueAsString(mappings));
        } catch (Exception e) {
            if (LoggerHolder.LOGGER != null) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("failed to serialize proxyPass mappings")
                        .setThrowable(e)
                );
            }
        }
    }

    public static Long globalResponseDelayMillis() {
        String value = readPropertyHierarchically(PROPERTIES, MOCKSERVER_GLOBAL_RESPONSE_DELAY_MILLIS, "MOCKSERVER_GLOBAL_RESPONSE_DELAY_MILLIS", "");
        if (isBlank(value)) {
            return null;
        }
        try {
            long millis = Long.parseLong(value);
            if (millis < 0) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("invalid value {} for globalResponseDelayMillis, must be >= 0")
                        .setArguments(value)
                );
                return null;
            }
            return millis;
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    public static void globalResponseDelayMillis(Long millis) {
        if (millis != null) {
            if (millis < 0) {
                throw new IllegalArgumentException("globalResponseDelayMillis must be >= 0, got: " + millis);
            }
            setProperty(MOCKSERVER_GLOBAL_RESPONSE_DELAY_MILLIS, "" + millis);
        } else {
            clearProperty(MOCKSERVER_GLOBAL_RESPONSE_DELAY_MILLIS);
        }
    }

    /**
     * <p>The required password for proxy authentication to MockServer</p>
     * <p><strong>Note:</strong> <a target="_blank" href="https://www.oracle.com/java/technologies/javase/8u111-relnotes.html">8u111 Update Release Notes</a> state that the Basic authentication scheme has been deactivated when setting up an HTTPS tunnel.  To resolve this clear or set to an empty string the following system properties: <code class="inline code">jdk.http.auth.tunneling.disabledSchemes</code> and <code class="inline code">jdk.http.auth.proxying.disabledSchemes</code>.</p>
     * <p>
     * The default is ""
     *
     * @param proxyAuthenticationPassword required password for proxy authentication to MockServer
     */
    public static void proxyAuthenticationPassword(String proxyAuthenticationPassword) {
        setProperty(MOCKSERVER_PROXY_AUTHENTICATION_PASSWORD, proxyAuthenticationPassword);
    }

    // liveness

    public static String livenessHttpGetPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LIVENESS_HTTP_GET_PATH, "MOCKSERVER_LIVENESS_HTTP_GET_PATH", "");
    }

    /**
     * Path to support HTTP GET requests for status response (also available on PUT /mockserver/status).
     * <p>
     * If this value is not modified then only PUT /mockserver/status but is a none blank value is provided for this value then GET requests to this path will return the 200 Ok status response showing the MockServer version and bound ports.
     * <p>
     * A GET request to this path will be matched before any expectation matching or proxying of requests.
     * <p>
     * The default is ""
     *
     * @param livenessPath path to support HTTP GET requests for status response
     */
    public static void livenessHttpGetPath(String livenessPath) {
        setProperty(MOCKSERVER_LIVENESS_HTTP_GET_PATH, livenessPath);
    }

    // control plane authentication

    public static boolean controlPlaneTLSMutualAuthenticationRequired() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_REQUIRED, "MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_REQUIRED", "false"));
    }

    /**
     * Require mTLS (also called client authentication and two-way TLS) for all control plane requests
     *
     * @param enable TLS mutual authentication for all control plane requests
     */
    public static void controlPlaneTLSMutualAuthenticationRequired(boolean enable) {
        setProperty(MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_REQUIRED, "" + enable);
    }

    public static String controlPlaneTLSMutualAuthenticationCAChain() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN, "MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN", "");
    }

    /**
     * File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for control plane mTLS authentication
     * <p>
     * The X.509 Certificate Chain is for trusting (i.e. signature verification of) Client X.509 Certificates, the certificate chain must be a X509 PEM file.
     * <p>
     * This certificate chain will be used for to performs mTLS (client authentication) for inbound TLS connections if controlPlaneTLSMutualAuthenticationRequired is enabled
     *
     * @param trustCertificateChain File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for Trusting (i.e. signature verification of) Client X.509 Certificates
     */
    public static void controlPlaneTLSMutualAuthenticationCAChain(String trustCertificateChain) {
        setProperty(MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN, "" + trustCertificateChain);
    }

    public static String controlPlanePrivateKeyPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_TLS_PRIVATE_KEY_PATH, "MOCKSERVER_CONTROL_PLANE_TLS_PRIVATE_KEY_PATH", "");
    }

    /**
     * File system path or classpath location of a fixed custom private key for control plane connections using mTLS for authentication.
     * <p>
     * The private key must be a PKCS#8 or PKCS#1 PEM file and must be the private key corresponding to the controlPlaneX509CertificatePath X509 (public key) configuration.
     * The controlPlaneTLSMutualAuthenticationCAChain configuration must be the Certificate Authority for the corresponding X509 certificate (i.e. able to valid its signature).
     * <p>
     * To convert a PKCS#1 (i.e. default for Bouncy Castle) to a PKCS#8 the following command can be used: openssl pkcs8 -topk8 -inform PEM -in private_key_PKCS_1.pem -out private_key_PKCS_8.pem -nocrypt
     * <p>
     * This configuration will be ignored unless x509CertificatePath is also set.
     *
     * @param privateKeyPath location of the PKCS#8 PEM file containing the private key
     */
    public static void controlPlanePrivateKeyPath(String privateKeyPath) {
        fileExists(privateKeyPath);
        setProperty(MOCKSERVER_CONTROL_PLANE_TLS_PRIVATE_KEY_PATH, privateKeyPath);
    }


    public static String controlPlaneX509CertificatePath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_TLS_X509_CERTIFICATE_PATH, "MOCKSERVER_CONTROL_PLANE_TLS_X509_CERTIFICATE_PATH", "");
    }

    /**
     * File system path or classpath location of a fixed custom X.509 Certificate for control plane connections using mTLS for authentication.
     * <p>
     * The certificate must be a X509 PEM file and must be the public key corresponding to the controlPlanePrivateKeyPath private key configuration.
     * The controlPlaneTLSMutualAuthenticationCAChain configuration must be the Certificate Authority for this certificate (i.e. able to valid its signature).
     * <p>
     * This configuration will be ignored unless privateKeyPath is also set.
     *
     * @param x509CertificatePath location of the PEM file containing the X509 certificate
     */
    public static void controlPlaneX509CertificatePath(String x509CertificatePath) {
        fileExists(x509CertificatePath);
        setProperty(MOCKSERVER_CONTROL_PLANE_TLS_X509_CERTIFICATE_PATH, x509CertificatePath);
    }

    public static boolean controlPlaneJWTAuthenticationRequired() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED, "MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED", "false"));
    }

    /**
     * <p>
     * Require JWT authentication for all control plane requests
     * </p>
     *
     * @param enable TLS mutual authentication for all control plane requests
     */
    public static void controlPlaneJWTAuthenticationRequired(boolean enable) {
        setProperty(MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED, "" + enable);
    }

    public static String controlPlaneJWTAuthenticationJWKSource() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_JWK_SOURCE, "MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_JWK_SOURCE", "");
    }

    /**
     * <p>
     * JWK source used when JWT authentication is enabled for control plane requests
     * </p>
     * <p>
     * JWK source can be a file system path, classpath location or a URL
     * </p>
     * <p>
     * See: https://openid.net/specs/draft-jones-json-web-key-03.html
     * </p>
     *
     * @param controlPlaneJWTAuthenticationJWKSource file system path, classpath location or a URL of JWK source
     */
    public static void controlPlaneJWTAuthenticationJWKSource(String controlPlaneJWTAuthenticationJWKSource) {
        setProperty(MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_JWK_SOURCE, "" + controlPlaneJWTAuthenticationJWKSource);
    }

    public static String controlPlaneJWTAuthenticationExpectedAudience() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_EXPECTED_AUDIENCE, "MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_EXPECTED_AUDIENCE", "");
    }

    /**
     * <p>
     * Audience claim (i.e. aud) required when JWT authentication is enabled for control plane requests
     * </p>
     *
     * @param controlPlaneJWTAuthenticationExpectedAudience required value for audience claim (i.e. aud)
     */
    public static void controlPlaneJWTAuthenticationExpectedAudience(String controlPlaneJWTAuthenticationExpectedAudience) {
        setProperty(MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_EXPECTED_AUDIENCE, "" + controlPlaneJWTAuthenticationExpectedAudience);
    }

    @SuppressWarnings("UnstableApiUsage")
    public static Map<String, String> controlPlaneJWTAuthenticationMatchingClaims() {
        String jwtAuthenticationMatchingClaims = readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_MATCHING_CLAIMS, "MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_MATCHING_CLAIMS", "");
        if (isNotBlank(jwtAuthenticationMatchingClaims)) {
            return Splitter.on(",").withKeyValueSeparator("=").split(jwtAuthenticationMatchingClaims);
        } else {
            return ImmutableMap.of();
        }
    }

    /**
     * <p>
     * Matching claims expected when JWT authentication is enabled for control plane requests
     * </p>
     * <p>
     * Value should be string with comma separated key=value items, for example: scope=internal public,sub=some_subject
     * </p>
     *
     * @param controlPlaneJWTAuthenticationMatchingClaims required values for claims
     */
    public static void controlPlaneJWTAuthenticationMatchingClaims(Map<String, String> controlPlaneJWTAuthenticationMatchingClaims) {
        setProperty(MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_MATCHING_CLAIMS, Joiner.on(",").withKeyValueSeparator("=").join(controlPlaneJWTAuthenticationMatchingClaims));
    }

    public static Set<String> controlPlaneJWTAuthenticationRequiredClaims() {
        String jwtAuthenticationRequiredClaims = readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED_CLAIMS, "MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED_CLAIMS", "");
        if (isNotBlank(jwtAuthenticationRequiredClaims)) {
            return Sets.newConcurrentHashSet(Arrays.asList(jwtAuthenticationRequiredClaims.split(",")));
        } else {
            return ImmutableSet.of();
        }
    }

    /**
     * <p>
     * Required claims that should exist (i.e. with any value) when JWT authentication is enabled for control plane requests
     * </p>
     * <p>
     * Value should be string with comma separated values, for example: scope,sub
     * </p>
     *
     * @param controlPlaneJWTAuthenticationRequiredClaims required claims
     */
    public static void controlPlaneJWTAuthenticationRequiredClaims(Set<String> controlPlaneJWTAuthenticationRequiredClaims) {
        setProperty(MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED_CLAIMS, Joiner.on(",").join(controlPlaneJWTAuthenticationRequiredClaims));
    }

    // TLS

    /**
     * <p>Proactively initialise TLS during start to ensure that if dynamicallyCreateCertificateAuthorityCertificate is enabled the Certificate Authority X.509 Certificate and Private Key will be created during start up and not when the first TLS connection is received.</p>
     * <p>This setting will also ensure any configured private key and X.509 will be loaded during start up and not when the first TLS connection is received to give immediate feedback on any related TLS configuration errors.</p>
     *
     * @param enable proactively initialise TLS at startup
     */
    public static void proactivelyInitialiseTLS(boolean enable) {
        setProperty(MOCKSERVER_PROACTIVELY_INITIALISE_TLS, "" + enable);
    }

    public static boolean proactivelyInitialiseTLS() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROACTIVELY_INITIALISE_TLS, "MOCKSERVER_PROACTIVELY_INITIALISE_TLS", "false"));
    }

    public static String tlsProtocols() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_TLS_PROTOCOLS, "MOCKSERVER_TLS_PROTOCOLS", "TLSv1,TLSv1.1,TLSv1.2");
    }

    /**
     * Comma seperated list of TLS protocols, by default TLSv1,TLSv1.1,TLSv1.2
     *
     * @param tlsProtocols comma seperated list of TLS protocols
     */
    public static  void tlsProtocols(String tlsProtocols) {
        setProperty(MOCKSERVER_TLS_PROTOCOLS, tlsProtocols);
    }

    public static boolean tlsAllowInsecureProtocols() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_TLS_ALLOW_INSECURE_PROTOCOLS, "MOCKSERVER_TLS_ALLOW_INSECURE_PROTOCOLS", "" + true));
    }

    /**
     * Whether to allow TLSv1 and TLSv1.1 in the effective TLS protocols list.
     * <p>
     * Both protocols are deprecated by RFC 8996 and vulnerable to BEAST and POODLE.
     * The default is true for backwards compatibility — MockServer's
     * {@link #tlsProtocols} default still includes them. Set this to false to opt
     * into a hardened profile: any "TLSv1" or "TLSv1.1" entries in
     * {@link #tlsProtocols} are filtered out before the SSL context is built.
     * <p>
     * A future major release is expected to flip this default to false.
     *
     * @param allow if true, TLSv1 and TLSv1.1 are honoured in {@link #tlsProtocols}; if false, they are stripped
     */
    public static void tlsAllowInsecureProtocols(boolean allow) {
        setProperty(MOCKSERVER_TLS_ALLOW_INSECURE_PROTOCOLS, "" + allow);
    }

    public static boolean dynamicallyCreateCertificateAuthorityCertificate() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DYNAMICALLY_CREATE_CERTIFICATE_AUTHORITY_CERTIFICATE, "MOCKSERVER_DYNAMICALLY_CREATE_CERTIFICATE_AUTHORITY_CERTIFICATE", "false"));
    }

    /**
     * Enable dynamic creation of Certificate Authority X509 certificate and private key.
     * <p>
     * Enable this property to increase the security of trusting the MockServer Certificate Authority X509 by ensuring a local dynamic value is used instead of the public value in the MockServer git repo.
     * <p>
     * These PEM files will be created and saved in the directory specified with configuration property directoryToSaveDynamicSSLCertificate.
     *
     * @param enable dynamic creation of Certificate Authority X509 certificate and private key.
     */
    public static void dynamicallyCreateCertificateAuthorityCertificate(boolean enable) {
        setProperty(MOCKSERVER_DYNAMICALLY_CREATE_CERTIFICATE_AUTHORITY_CERTIFICATE, "" + enable);
    }

    public static String directoryToSaveDynamicSSLCertificate() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CERTIFICATE_DIRECTORY_TO_SAVE_DYNAMIC_SSL_CERTIFICATE, "MOCKSERVER_CERTIFICATE_DIRECTORY_TO_SAVE_DYNAMIC_SSL_CERTIFICATE", ".");
    }

    /**
     * Directory used to save the dynamically generated Certificate Authority X.509 Certificate and Private Key.
     *
     * @param directoryToSaveDynamicSSLCertificate directory to save Certificate Authority X.509 Certificate and Private Key
     */
    public static void directoryToSaveDynamicSSLCertificate(String directoryToSaveDynamicSSLCertificate) {
        fileExists(directoryToSaveDynamicSSLCertificate);
        setProperty(MOCKSERVER_CERTIFICATE_DIRECTORY_TO_SAVE_DYNAMIC_SSL_CERTIFICATE, directoryToSaveDynamicSSLCertificate);
    }

    /**
     * Prevent certificates from dynamically updating when domain list changes
     *
     * @param prevent prevent certificates from dynamically updating when domain list changes
     */
    public static void preventCertificateDynamicUpdate(boolean prevent) {
        setProperty(MOCKSERVER_PREVENT_CERTIFICATE_DYNAMIC_UPDATE, "" + prevent);
    }

    public static boolean preventCertificateDynamicUpdate() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_PREVENT_CERTIFICATE_DYNAMIC_UPDATE, "MOCKSERVER_PREVENT_CERTIFICATE_DYNAMIC_UPDATE", "false"));
    }

    public static String sslCertificateDomainName() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_SSL_CERTIFICATE_DOMAIN_NAME, "MOCKSERVER_SSL_CERTIFICATE_DOMAIN_NAME", KeyAndCertificateFactory.CERTIFICATE_DOMAIN);
    }

    /**
     * The domain name for auto-generate TLS certificates
     * <p>
     * The default is "localhost"
     *
     * @param domainName domain name for auto-generate TLS certificates
     */
    public static void sslCertificateDomainName(String domainName) {
        setProperty(MOCKSERVER_SSL_CERTIFICATE_DOMAIN_NAME, domainName);
    }

    /**
     * The Subject Alternative Name (SAN) domain names for auto-generate TLS certificates as a comma separated list
     * <p>
     * The default is "localhost"
     *
     * @param sslSubjectAlternativeNameDomains Subject Alternative Name (SAN) domain names for auto-generate TLS certificates
     */
    public static void sslSubjectAlternativeNameDomains(Set<String> sslSubjectAlternativeNameDomains) {
        setProperty(MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_DOMAINS, Joiner.on(",").join(sslSubjectAlternativeNameDomains));
    }

    public static Set<String> sslSubjectAlternativeNameDomains() {
        return Sets.newConcurrentHashSet(Arrays.asList(readPropertyHierarchically(PROPERTIES, MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_DOMAINS, "MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_DOMAINS", "localhost").split(",")));
    }

    /**
     * <p>The Subject Alternative Name (SAN) IP addresses for auto-generate TLS certificates as a comma separated list</p>
     *
     * <p>The default is "127.0.0.1,0.0.0.0"</p>
     *
     * @param sslSubjectAlternativeNameIps Subject Alternative Name (SAN) IP addresses for auto-generate TLS certificates
     */
    public static void sslSubjectAlternativeNameIps(Set<String> sslSubjectAlternativeNameIps) {
        setProperty(MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_IPS, Joiner.on(",").join(sslSubjectAlternativeNameIps));
    }

    public static Set<String> sslSubjectAlternativeNameIps() {
        return Sets.newConcurrentHashSet(Arrays.asList(readPropertyHierarchically(PROPERTIES, MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_IPS, "MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_IPS", "127.0.0.1,0.0.0.0").split(",")));
    }

    public static String certificateAuthorityPrivateKey() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CERTIFICATE_AUTHORITY_PRIVATE_KEY, "MOCKSERVER_CERTIFICATE_AUTHORITY_PRIVATE_KEY", DEFAULT_CERTIFICATE_AUTHORITY_PRIVATE_KEY);
    }

    /**
     * File system path or classpath location of custom Private Key for Certificate Authority for TLS, the private key must be a PKCS#8 or PKCS#1 PEM file and must match the certificateAuthorityCertificate
     * To convert a PKCS#1 (i.e. default for Bouncy Castle) to a PKCS#8 the following command can be used: openssl pkcs8 -topk8 -inform PEM -in private_key_PKCS_1.pem -out private_key_PKCS_8.pem -nocrypt
     *
     * @param certificateAuthorityPrivateKey location of the PEM file containing the certificate authority private key
     */
    public static void certificateAuthorityPrivateKey(String certificateAuthorityPrivateKey) {
        // Static setter validates eagerly because the value is expected to come from
        // user-supplied configuration (system property, env var, or programmatic call).
        // The instance setter on Configuration intentionally does NOT validate, since
        // BCKeyAndCertificateFactory uses it to store the destination path before the
        // dynamic CA file is written.
        fileExists(certificateAuthorityPrivateKey);
        setProperty(MOCKSERVER_CERTIFICATE_AUTHORITY_PRIVATE_KEY, certificateAuthorityPrivateKey);
    }

    public static String certificateAuthorityCertificate() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CERTIFICATE_AUTHORITY_X509_CERTIFICATE, "MOCKSERVER_CERTIFICATE_AUTHORITY_X509_CERTIFICATE", DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE);
    }

    /**
     * File system path or classpath location of custom X.509 Certificate for Certificate Authority for TLS, the certificate must be a X509 PEM file and must match the certificateAuthorityPrivateKey
     *
     * @param certificateAuthorityCertificate location of the PEM file containing the certificate authority X509 certificate
     */
    public static void certificateAuthorityCertificate(String certificateAuthorityCertificate) {
        // See the comment on certificateAuthorityPrivateKey above for the rationale on
        // why the static setter validates and the matching instance setter does not.
        fileExists(certificateAuthorityCertificate);
        setProperty(MOCKSERVER_CERTIFICATE_AUTHORITY_X509_CERTIFICATE, certificateAuthorityCertificate);
    }

    public static String privateKeyPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_TLS_PRIVATE_KEY_PATH, "MOCKSERVER_TLS_PRIVATE_KEY_PATH", "");
    }

    /**
     * File system path or classpath location of a fixed custom private key for TLS connections into MockServer.
     * <p>
     * The private key must be a PKCS#8 or PKCS#1 PEM file and must be the private key corresponding to the x509CertificatePath X509 (public key) configuration.
     * The certificateAuthorityCertificate configuration must be the Certificate Authority for the corresponding X509 certificate (i.e. able to valid its signature), see: x509CertificatePath.
     * <p>
     * To convert a PKCS#1 (i.e. default for Bouncy Castle) to a PKCS#8 the following command can be used: openssl pkcs8 -topk8 -inform PEM -in private_key_PKCS_1.pem -out private_key_PKCS_8.pem -nocrypt
     * <p>
     * This configuration will be ignored unless x509CertificatePath is also set.
     *
     * @param privateKeyPath location of the PKCS#8 PEM file containing the private key
     */
    public static void privateKeyPath(String privateKeyPath) {
        // See the comment on certificateAuthorityPrivateKey above — instance setter is
        // intentionally lenient because dynamic SSL generation pre-stores the destination.
        fileExists(privateKeyPath);
        setProperty(MOCKSERVER_TLS_PRIVATE_KEY_PATH, privateKeyPath);
    }


    public static String x509CertificatePath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_TLS_X509_CERTIFICATE_PATH, "MOCKSERVER_TLS_X509_CERTIFICATE_PATH", "");
    }

    /**
     * File system path or classpath location of a fixed custom X.509 Certificate for TLS connections into MockServer.
     * <p>
     * The certificate must be a X509 PEM file and must be the public key corresponding to the privateKeyPath private key configuration.
     * The certificateAuthorityCertificate configuration must be the Certificate Authority for this certificate (i.e. able to valid its signature).
     * <p>
     * This configuration will be ignored unless privateKeyPath is also set.
     *
     * @param x509CertificatePath location of the PEM file containing the X509 certificate
     */
    public static void x509CertificatePath(String x509CertificatePath) {
        // See the comment on certificateAuthorityPrivateKey above — instance setter is
        // intentionally lenient because dynamic SSL generation pre-stores the destination.
        fileExists(x509CertificatePath);
        setProperty(MOCKSERVER_TLS_X509_CERTIFICATE_PATH, x509CertificatePath);
    }

    public static boolean tlsMutualAuthenticationRequired() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_REQUIRED, "MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_REQUIRED", "false"));
    }

    /**
     * Require mTLS (also called client authentication and two-way TLS) for all TLS connections / HTTPS requests to MockServer
     *
     * @param enable TLS mutual authentication
     */
    public static void tlsMutualAuthenticationRequired(boolean enable) {
        setProperty(MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_REQUIRED, "" + enable);
    }

    public static String tlsMutualAuthenticationCertificateChain() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN, "MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN", "");
    }

    /**
     * File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for trusting (i.e. signature verification of) Client X.509 Certificates, the certificate chain must be a X509 PEM file.
     * <p>
     * This certificate chain will be used if MockServer performs mTLS (client authentication) for inbound TLS connections because tlsMutualAuthenticationRequired is enabled
     *
     * @param trustCertificateChain File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for Trusting (i.e. signature verification of) Client X.509 Certificates
     */
    public static void tlsMutualAuthenticationCertificateChain(String trustCertificateChain) {
        fileExists(trustCertificateChain);
        setProperty(MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN, "" + trustCertificateChain);
    }

    public static ForwardProxyTLSX509CertificatesTrustManager forwardProxyTLSX509CertificatesTrustManagerType() {
        String forwardProxyTlsX509CertificatesTrustManagerType = readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATES_TRUST_MANAGER_TYPE, "MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATES_TRUST_MANAGER_TYPE", "ANY");
        try {
            return ForwardProxyTLSX509CertificatesTrustManager.valueOf(forwardProxyTlsX509CertificatesTrustManagerType);
        } catch (Throwable ignore) {
            throw new IllegalArgumentException("Invalid value for ForwardProxyTLSX509CertificatesTrustManager \"" + forwardProxyTlsX509CertificatesTrustManagerType + "\" the only supported values are: " + Arrays.stream(ForwardProxyTLSX509CertificatesTrustManager.values()).map(Enum::name).collect(Collectors.toList()));
        }
    }

    /**
     * Configure trusted set of certificates for forwarded or proxied requests.
     * <p>
     * MockServer will only be able to establish a TLS connection to endpoints that have a trusted X509 certificate according to the trust manager type, as follows:
     * <p>
     * <p>
     * ALL - Insecure will trust all X509 certificates and not perform host name verification.
     * JVM - Will trust all X509 certificates trust by the JVM.
     * CUSTOM - Will trust all X509 certificates specified in forwardProxyTLSCustomTrustX509Certificates configuration value.
     *
     * @param trustManagerType trusted set of certificates for forwarded or proxied requests, allowed values: ALL, JVM, CUSTOM.
     */
    public static void forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager trustManagerType) {
        setProperty(MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATES_TRUST_MANAGER_TYPE, trustManagerType.name());
    }

    public static boolean forwardProxyBlockPrivateNetworks() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_BLOCK_PRIVATE_NETWORKS, "MOCKSERVER_FORWARD_PROXY_BLOCK_PRIVATE_NETWORKS", "" + false));
    }

    /**
     * When set to true, MockServer rejects forward and proxy targets that resolve to
     * loopback, link-local, RFC 1918 private, or cloud metadata addresses
     * (such as 169.254.169.254). This blocks server-side request forgery (SSRF) attacks
     * where a malicious expectation forwards through MockServer to internal infrastructure.
     * <p>
     * The default is false because MockServer is primarily used to mock services in
     * private or loopback test networks (Docker bridges, Kubernetes service IPs,
     * localhost), so blocking those targets by default would break the common case.
     * Enable this in hardened or multi-tenant deployments where untrusted callers can
     * register expectations.
     *
     * @param block if true, block forwarding to private or metadata addresses
     */
    public static void forwardProxyBlockPrivateNetworks(boolean block) {
        setProperty(MOCKSERVER_FORWARD_PROXY_BLOCK_PRIVATE_NETWORKS, "" + block);
    }

    public static String forwardProxyTLSCustomTrustX509Certificates() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_TLS_CUSTOM_TRUST_X509_CERTIFICATES, "MOCKSERVER_FORWARD_PROXY_TLS_CUSTOM_TRUST_X509_CERTIFICATES", "");
    }

    /**
     * File system path or classpath location of custom file for trusted X509 Certificate Authority roots for forwarded or proxied requests, the certificate chain must be a X509 PEM file.
     * <p>
     * MockServer will only be able to establish a TLS connection to endpoints that have an X509 certificate chain that is signed by one of the provided custom
     * certificates, i.e. where a path can be established from the endpoints X509 certificate to one or more of the custom X509 certificates provided.
     *
     * @param customX509Certificates custom set of trusted X509 certificate authority roots for forwarded or proxied requests in PEM format.
     */
    public static void forwardProxyTLSCustomTrustX509Certificates(String customX509Certificates) {
        fileExists(customX509Certificates);
        setProperty(MOCKSERVER_FORWARD_PROXY_TLS_CUSTOM_TRUST_X509_CERTIFICATES, customX509Certificates);
    }

    public static String forwardProxyPrivateKey() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_TLS_PRIVATE_KEY, "MOCKSERVER_FORWARD_PROXY_TLS_PRIVATE_KEY", "");
    }

    /**
     * File system path or classpath location of custom Private Key for proxied TLS connections out of MockServer, the private key must be a PKCS#8 or PKCS#1 PEM file
     * <p>
     * To convert a PKCS#1 (i.e. default for Bouncy Castle) to a PKCS#8 the following command can be used: openssl pkcs8 -topk8 -inform PEM -in private_key_PKCS_1.pem -out private_key_PKCS_8.pem -nocrypt
     * <p>
     * This private key will be used if MockServer needs to perform mTLS (client authentication) for outbound TLS connections.
     *
     * @param privateKey location of the PEM file containing the private key
     */
    public static void forwardProxyPrivateKey(String privateKey) {
        fileExists(privateKey);
        setProperty(MOCKSERVER_FORWARD_PROXY_TLS_PRIVATE_KEY, privateKey);
    }

    public static String forwardProxyCertificateChain() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATE_CHAIN, "MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATE_CHAIN", "");
    }

    /**
     * File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for Trusting (i.e. signature verification of) Client X.509 Certificates, the certificate chain must be a X509 PEM file.
     * <p>
     * This certificate chain will be used if MockServer needs to perform mTLS (client authentication) for outbound TLS connections.
     *
     * @param certificateChain location of the PEM file containing the certificate chain
     */
    public static void forwardProxyCertificateChain(String certificateChain) {
        fileExists(certificateChain);
        setProperty(MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATE_CHAIN, certificateChain);
    }

    @SuppressWarnings("ConstantConditions")
    static void fileExists(String file) {
        try {
            if (isNotBlank(file) && FileReader.openStreamToFileFromClassPathOrPath(file) == null) {
                throw new RuntimeException(file + " does not exist or is not accessible");
            }
        } catch (FileNotFoundException e) {
            if (!new File(file).exists()) {
                throw new RuntimeException(file + " does not exist or is not accessible");
            }
        }
    }

    private static void validateHostAndPortAndSetProperty(String hostAndPort, String mockserverSocksProxy) {
        if (isNotBlank(hostAndPort)) {
            if (hostAndPort.startsWith("/")) {
                hostAndPort = StringUtils.substringAfter(hostAndPort, "/");
            }
            String errorMessage = "Invalid property value \"" + hostAndPort + "\" for \"" + mockserverSocksProxy + "\" must include <host>:<port> for example \"127.0.0.1:1090\" or \"localhost:1090\"";
            try {
                URI uri = new URI("https://" + hostAndPort);
                if (uri.getHost() == null || uri.getPort() == -1) {
                    throw new IllegalArgumentException(errorMessage);
                } else {
                    setProperty(mockserverSocksProxy, hostAndPort);
                }
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException(errorMessage);
            }
        } else {
            clearProperty(mockserverSocksProxy);
        }
    }

    private static InetSocketAddress readInetSocketAddressProperty(String key, String environmentVariableKey) {
        InetSocketAddress inetSocketAddress = null;
        String proxy = readPropertyHierarchically(PROPERTIES, key, environmentVariableKey, "");
        if (isNotBlank(proxy)) {
            String[] proxyParts = org.mockserver.model.HttpRequest.splitHostPort(proxy);
            if (proxyParts.length > 1) {
                try {
                    inetSocketAddress = new InetSocketAddress(proxyParts[0], Integer.parseInt(proxyParts[1]));
                } catch (NumberFormatException nfe) {
                    LoggerHolder.LOGGER.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("NumberFormatException converting value \"" + proxyParts[1] + "\" into an integer")
                            .setThrowable(nfe)
                    );
                }
            }
        }
        return inetSocketAddress;
    }

    private static Integer readIntegerProperty(String key, String environmentVariableKey, int defaultValue) {
        try {
            return Integer.parseInt(readPropertyHierarchically(PROPERTIES, key, environmentVariableKey, "" + defaultValue));
        } catch (NumberFormatException nfe) {
            LoggerHolder.LOGGER.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("NumberFormatException converting " + key + " with value [" + readPropertyHierarchically(PROPERTIES, key, environmentVariableKey, "" + defaultValue) + "]")
                    .setThrowable(nfe)
            );
            return defaultValue;
        }
    }

    private static Long readLongProperty(String key, String environmentVariableKey, long defaultValue) {
        try {
            return Long.parseLong(readPropertyHierarchically(PROPERTIES, key, environmentVariableKey, "" + defaultValue));
        } catch (NumberFormatException nfe) {
            LoggerHolder.LOGGER.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("NumberFormatException converting " + key + " with value [" + readPropertyHierarchically(PROPERTIES, key, environmentVariableKey, "" + defaultValue) + "]")
                    .setThrowable(nfe)
            );
            return defaultValue;
        }
    }

    static final String REDACTED_VALUE = "***REDACTED***";

    private static final Set<String> SENSITIVE_SUBSTRINGS = Stream.of(
        "password",
        "secret",
        "accesskey",
        "access_key",
        "apikey",
        "api_key",
        "connectionstring",
        "connection_string",
        "token",
        "privatekey",
        "private_key",
        "credential",
        "passphrase"
    ).collect(Collectors.toCollection(LinkedHashSet::new));

    /**
     * Returns {@code true} when the property name (with or without the
     * {@code mockserver.} prefix) contains a substring that indicates the
     * value is a secret and must not be logged verbatim.
     */
    static boolean isSensitivePropertyName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        // Strip prefix so "mockserver.llmApiKey" matches "apikey"
        if (lower.startsWith("mockserver.")) {
            lower = lower.substring("mockserver.".length());
        }
        for (String sensitive : SENSITIVE_SUBSTRINGS) {
            if (lower.contains(sensitive)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("ConstantConditions")
    private static Properties readPropertyFile() {

        Properties properties = new Properties();

        if (propertyFile().endsWith(".json")) {
            properties = readJsonPropertyFile();
        } else {
            try (InputStream inputStream = ConfigurationProperties.class.getClassLoader().getResourceAsStream(propertyFile())) {
                if (inputStream != null) {
                    try {
                        properties.load(inputStream);
                    } catch (IOException e) {
                        e.printStackTrace();
                        if (LoggerHolder.LOGGER != null) {
                            LoggerHolder.LOGGER.logEvent(
                                new LogEntry()
                                    .setAlwaysLog(true)
                                    .setLogLevel(Level.ERROR)
                                    .setMessageFormat("exception loading property file [" + propertyFile() + "]")
                                    .setThrowable(e)
                            );
                        }
                    }
                } else {
                    if (LoggerHolder.LOGGER != null && MockServerLogger.isEnabled(DEBUG)) {
                        LoggerHolder.LOGGER.logEvent(
                            new LogEntry()
                                .setType(SERVER_CONFIGURATION)
                                .setLogLevel(DEBUG)
                                .setMessageFormat("property file not found on classpath using path [" + propertyFile() + "]")
                        );
                    }
                    try {
                        properties.load(new FileInputStream(propertyFile()));
                    } catch (FileNotFoundException e) {
                        if (LoggerHolder.LOGGER != null && MockServerLogger.isEnabled(DEBUG)) {
                            LoggerHolder.LOGGER.logEvent(
                                new LogEntry()
                                    .setType(SERVER_CONFIGURATION)
                                    .setLogLevel(DEBUG)
                                    .setMessageFormat("property file not found using path [" + propertyFile() + "]")
                            );
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                        if (LoggerHolder.LOGGER != null) {
                            LoggerHolder.LOGGER.logEvent(
                                new LogEntry()
                                    .setAlwaysLog(true)
                                    .setLogLevel(Level.ERROR)
                                    .setMessageFormat("exception loading property file [" + propertyFile() + "]")
                                    .setThrowable(e)
                            );
                        }
                    }
                }
            } catch (IOException ioe) {
                // ignore
            }
        }

        if (!properties.isEmpty()) {
            Enumeration<?> propertyNames = properties.propertyNames();

            StringBuilder propertiesLogDump = new StringBuilder();
            propertiesLogDump.append("Reading properties from property file [").append(propertyFile()).append("]:").append(NEW_LINE);
            while (propertyNames.hasMoreElements()) {
                String propertyName = String.valueOf(propertyNames.nextElement());
                String displayValue = isSensitivePropertyName(propertyName) ? REDACTED_VALUE : properties.getProperty(propertyName);
                propertiesLogDump.append("  ").append(propertyName).append(" = ").append(displayValue).append(NEW_LINE);
            }

            Level logLevel = Level.valueOf(getSLF4JOrJavaLoggerToSLF4JLevelMapping().get(readPropertyHierarchically(properties, MOCKSERVER_LOG_LEVEL, "MOCKSERVER_LOG_LEVEL", DEFAULT_LOG_LEVEL).toUpperCase()));
            if (LoggerHolder.LOGGER != null && MockServerLogger.isEnabled(Level.INFO, logLevel)) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setAlwaysLog(true)
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setMessageFormat(propertiesLogDump.toString())
                );
            }
        }

        return properties;
    }

    @SuppressWarnings("unchecked")
    private static Properties readJsonPropertyFile() {
        Properties properties = new Properties();
        InputStream inputStream = ConfigurationProperties.class.getClassLoader().getResourceAsStream(propertyFile());
        try {
            if (inputStream == null) {
                try {
                    inputStream = new FileInputStream(propertyFile());
                } catch (FileNotFoundException e) {
                    if (LoggerHolder.LOGGER != null && MockServerLogger.isEnabled(DEBUG)) {
                        LoggerHolder.LOGGER.logEvent(
                            new LogEntry()
                                .setType(SERVER_CONFIGURATION)
                                .setLogLevel(DEBUG)
                                .setMessageFormat("JSON property file not found using path [" + propertyFile() + "]")
                        );
                    }
                    return properties;
                }
            }
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = org.mockserver.serialization.ObjectMapperFactory.createObjectMapper();
            Map<String, Object> jsonMap = objectMapper.readValue(inputStream, Map.class);
            for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
                String key = "mockserver." + entry.getKey();
                Object value = entry.getValue();
                if (value != null) {
                    if (value instanceof Collection) {
                        properties.setProperty(key, Joiner.on(",").join((Collection<?>) value));
                    } else if (value instanceof Map) {
                        Map<?, ?> mapValue = (Map<?, ?>) value;
                        properties.setProperty(key, mapValue.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining(",")));
                    } else {
                        properties.setProperty(key, String.valueOf(value));
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
            if (LoggerHolder.LOGGER != null) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setAlwaysLog(true)
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception loading JSON property file [" + propertyFile() + "]")
                        .setThrowable(e)
                );
            }
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        return properties;
    }

    private static Map<String, String> propertyCache;

    private static Map<String, String> getPropertyCache() {
        if (propertyCache == null) {
            propertyCache = new ConcurrentHashMap<>();
        }
        return propertyCache;
    }

    private static void setProperty(String systemPropertyKey, String value) {
        getPropertyCache().put(systemPropertyKey, value);
        System.setProperty(systemPropertyKey, value);
    }

    private static void clearProperty(String systemPropertyKey) {
        getPropertyCache().remove(systemPropertyKey);
        System.clearProperty(systemPropertyKey);
    }

    private static String readPropertyHierarchically(Properties properties, String systemPropertyKey, String environmentVariableKey, String defaultValue) {
        String cachedPropertyValue = getPropertyCache().get(systemPropertyKey);
        if (cachedPropertyValue != null) {
            return cachedPropertyValue;
        } else {
            if (isBlank(environmentVariableKey)) {
                throw new IllegalArgumentException("environment property name cannot be null for " + systemPropertyKey);
            }
            String defaultOrEnvironmentVariable = isNotBlank(System.getenv(environmentVariableKey)) ? System.getenv(environmentVariableKey) : defaultValue;
            String propertyValue = System.getProperty(systemPropertyKey, properties != null ? properties.getProperty(systemPropertyKey, defaultOrEnvironmentVariable) : defaultOrEnvironmentVariable);
            if (propertyValue != null && propertyValue.startsWith("\"") && propertyValue.endsWith("\"")) {
                propertyValue = propertyValue.replaceAll("^\"|\"$", "");
            }
            getPropertyCache().put(systemPropertyKey, propertyValue);
            return propertyValue;
        }
    }
}
