# Configuration Reference

MockServer is configured through a single mechanism — a flat set of named properties — exposed in four equivalent forms. The authoritative list of every property, with defaults and inline documentation, is the example file checked into the repo:

**[`mockserver/mockserver.example.properties`](../../mockserver/mockserver.example.properties)**

That file is the source of truth. When a property is added, removed, or renamed it MUST be reflected there with a short comment explaining what it does. This doc explains the mechanism around it — *how* a value reaches the running server, *how* the layers interact, and *which* code does the loading.

For the user-facing rendition of the same properties (with examples and cross-links), see [Configuration Properties on the website](https://www.mock-server.com/mock_server/configuration_properties.html).

## How values are loaded

`ConfigurationProperties` is a static holder in `mockserver/mockserver-core/src/main/java/org/mockserver/configuration/`. Every property has a typed getter (e.g. `serverPort()`, `tlsMutualAuthenticationRequired()`) that resolves the value in this order — first hit wins:

1. **JVM system property** — `-Dmockserver.serverPort=1080`
2. **Environment variable** — `MOCKSERVER_SERVER_PORT=1080` (upper-snake form of the system-property suffix)
3. **Properties file** — pointed to by `-Dmockserver.propertyFile=…` (default: `./mockserver.properties` if present)
4. **Built-in default** — coded into the typed getter

This means env vars override file properties, system properties override env vars, and explicit `Configuration` instance setters override all of the above (see "Instance-scoped configuration" below).

The loader logs the resolved property source on startup at `TRACE` — useful when a value isn't what you expect.

## The four equivalent forms

| Form | Use when | Example |
|------|----------|---------|
| Properties file | Shipping reproducible config alongside the JAR / Docker image; CI default; what `mockserver.example.properties` documents | `mockserver.serverPort=1080` |
| Environment variable | Container deployments where each setting is its own knob | `MOCKSERVER_SERVER_PORT=1080` |
| JVM system property | Overriding a single value at launch time (CLI, IDE) | `-Dmockserver.serverPort=1080` |
| Programmatic — `Configuration` instance | Embedded MockServer in tests; per-instance overrides without touching globals | `new Configuration().serverPort(1080)` |

The first three feed into the **static** `ConfigurationProperties`. The fourth uses a **per-instance** `Configuration` object that falls back to `ConfigurationProperties` for any unset field. Use the instance form for tests that run multiple MockServers in the same JVM with different settings.

## Property categories

`mockserver.example.properties` groups the 49 properties into these blocks (in file order):

| Category | Examples |
|----------|----------|
| Ports & proxy | `serverPort`, `proxyRemoteHost`, `proxyRemotePort` |
| Logging | `logLevel`, `disableSystemOut`, `detailedMatchFailures`, `compactLogFormat`, `metricsEnabled` |
| Memory usage | `maxExpectations`, `maxLogEntries`, `maxWebSocketExpectations`, `outputMemoryUsageCsv` |
| HTTP behaviour | `nioEventLoopThreadCount`, `actionHandlerThreadCount`, `webSocketClientEventLoopThreadCount`, `clientNioEventLoopThreadCount` |
| Initialisation | `initializationClass`, `initializationJsonPath`, `persistExpectations`, `persistedExpectationsPath` |
| CORS | `enableCORSForAPI`, `enableCORSForAllResponses`, `corsAllowOrigin`, `corsAllowMethods`, `corsAllowHeaders`, `corsAllowCredentials` |
| Proxy auth | `forwardHttpsProxy`, `forwardSocksProxy`, `proxyAuthenticationUsername`, `proxyAuthenticationPassword`, `proxyAuthenticationRealm` |
| Control-plane JWT auth | `controlPlaneJWTAuthenticationRequired`, `controlPlaneJWTAuthenticationJWKSource`, `controlPlaneJWTAuthenticationExpectedAudience` |
| TLS inbound — dynamic | `certificateAuthorityPrivateKey`, `certificateAuthorityCertificate`, `dynamicallyCreateCertificateAuthorityCertificate`, `directoryToSaveDynamicSSLCertificate`, `preventCertificateDynamicUpdate`, `sslCertificateDomainName`, `sslSubjectAlternativeNameDomains`, `sslSubjectAlternativeNameIps` |
| TLS inbound — fixed | `privateKeyPath`, `x509CertificatePath` |
| mTLS | `tlsMutualAuthenticationRequired`, `tlsMutualAuthenticationCertificateChain` |
| TLS outbound | `forwardProxyTLSX509CertificatesTrustManagerType`, `forwardProxyTLSCustomTrustX509Certificates`, `forwardProxyPrivateKey`, `forwardProxyCertificateChain` |
| Protocol selection | `tlsProtocols`, `proactivelyInitialiseTLS`, `useBouncyCastleForKeyAndCertificateGeneration`, `useSemicolonAsQueryParameterSeparator` |

The example file is short enough to read end-to-end (≈220 lines). Reading it once is the quickest way to discover what's tunable.

## Adding a property

Five places to touch — there are no implicit registrations.

1. **Typed getter + private setter** in `mockserver/mockserver-core/src/main/java/org/mockserver/configuration/ConfigurationProperties.java` — define the constant key, the system-property name, the env-var alias, and the default.
2. **Instance-scoped fluent setter** in `mockserver/mockserver-core/src/main/java/org/mockserver/configuration/Configuration.java`, with `fileExists(...)` if the value is a path (see the existing patterns; this guard is part of the [TLS validation contract](tls-and-security.md)).
3. **Documentation** in `mockserver/mockserver.example.properties` — same section ordering as above.
4. **Tests** in `mockserver/mockserver-core/src/test/java/org/mockserver/configuration/ConfigurationTest.java` covering: env-var → property, system-property → property, fluent setter → property, default, and any validation guard.
5. **Consumer docs** at `jekyll-www.mock-server.com/mock_server/configuration_properties.html` — keep the user-facing description aligned with the inline comment.

See [docs/code/domain-model.md](domain-model.md) for the wider configuration architecture and [docs/code/memory-management.md](memory-management.md) for the memory-ring-buffer properties specifically (they need extra care because the wrong values can OOM the JVM).
