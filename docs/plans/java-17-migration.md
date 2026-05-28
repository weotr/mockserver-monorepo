# Java 17 Migration — Remaining Work

**Status:** Java 17 floor and the Jakarta EE 10 / Servlet 6 mega-bump are landed. Cleanup and modernisation items below remain.

## What's already done

| Item | Where |
|------|-------|
| `maven.compiler.source` / `target` = 17 | `mockserver/pom.xml` (committed in `1e7012942`) |
| CI/Docker JDK aligned to 17 | `e71c1ca30` — CodeQL, Buildkite, scripts |
| Docker runtime on `gcr.io/distroless/java17` | `6f596d746` (digest-pinned) |
| Spring 5.3.39 → 7.0.7 | `mockserver/pom.xml` (landed in the jakarta mega-bump commit) |
| `javax.servlet:javax.servlet-api:4.0.1` → `jakarta.servlet:jakarta.servlet-api:6.1.0` | `mockserver/pom.xml` + 29 Java files migrated to `jakarta.servlet` (landed) |
| Tomcat 9.0.118 → 11.0.22 | `mockserver/pom.xml` (landed) |
| Jetty 9.4.58 → 12.1.9 | `mockserver/mockserver-examples/pom.xml` (landed) |
| Spring Boot 4 / Jersey 4 / Jakarta EE 10+ | `mockserver/mockserver-examples` (landed) |
| Dependabot/Snyk jakarta blocklist removed | `.github/dependabot.yml` + `docs/operations/security.md` (landed) |
| AGENTS.md Java compatibility policy rewritten | "jakarta namespace; full migration complete" (landed) |
| Tomcat 11 SSLHostConfig migration in 4 WAR/proxy-war tests | landed |
| Servlet 6 RFC 6265 cookie quote stripping in core decoder | landed |
| BouncyCastleProvider static-init in PEMToFile | landed |
| Netty 4.1 → 4.2.14.Final via netty-bom | landed |
| `.bak` files deleted | landed (`pom.xml.bak` in war + proxy-war) |

## Remaining work

### 1. Remove Nashorn fallback (~1 hour)

GraalVM polyglot 25.0.3 and `js:25.0.3` are already wired into `mockserver-core`. `JavaScriptTemplateEngine.createEngine()` still reflectively tries Nashorn first and falls back to GraalJS — on Java 17 this dead code path serves no purpose and pulls in 5 unnecessary jars.

| File | Change |
|------|--------|
| `mockserver-core/.../templates/engine/javascript/JavaScriptTemplateEngine.java` | Delete lines 37 (`ENGINE_NASHORN` constant), 65-86 (Nashorn reflective path), and the "add nashorn-core" hint in line 176 |
| `mockserver-core/.../templates/engine/javascript/bindings/ScriptBindings.java` | Verify still used by GraalJS bindings; delete if obsolete |
| `mockserver/pom.xml` | Remove `org.openjdk.nashorn:nashorn-core:15.7` from dependency management + 4 ASM shaded entries |
| `mockserver-core/pom.xml` | Remove `nashorn-core` dependency (currently line 145) |
| `mockserver-netty/pom.xml` (shade plugin) | Drop nashorn excludes |
| `JavaScriptTemplateEngineTest.java` | Remove `nashornAvailable()` guard branches |
| `jekyll-www.mock-server.com/mock_server/response_templates.html` | Replace Nashorn limitations section with full ES2023+ support note |

**Security guard to retain:** `JavaScriptTemplateEngine` already sets `polyglot.js.allowHostAccess` + `allowHostClassLookup` bound to `isClassAllowed()`. When collapsing to a direct GraalVM `Context.Builder`, also add `HostAccess.EXPLICIT` (or a scoped `HostAccess` policy) so method/field access and reflection are restricted — `allowHostClassLookup` alone only gates class resolution.

### 2. Re-evaluate `--add-exports` compiler flags (~30 min)

`mockserver/pom.xml:938-939` still passes:

```
--add-exports=java.base/sun.security.x509=ALL-UNNAMED
--add-exports=java.base/sun.security.util=ALL-UNNAMED
```

These exist because of TLS certificate-internals access in `mockserver-core`. On Java 17 the public API surface in `java.security.cert` and BouncyCastle covers most use cases. Tasks:

- Audit `mockserver-core/.../socket/tls/` for `sun.security.x509.*` / `sun.security.util.*` references
- If feasible, refactor to public API or BouncyCastle and drop both flags
- If not feasible, leave flags in place — they remain valid on Java 17

### 3. Document ZGC for Docker (~30 min)

Java 17 ships production-ready ZGC. No code changes — purely documentation.

| File | Change |
|------|--------|
| `jekyll-www.mock-server.com/mock_server/_includes/performance_configuration.html` | Add ZGC tuning note: `-XX:+UseZGC` for sub-ms pauses, especially with large `maxLogEntries` |
| `docs/operations/performance-tuning.md` | Document ZGC recommendation alongside existing JVM flags guidance |
| `docker/`, `helm/` (optional) | Consider opt-in env var hint, but don't change defaults |

### 4. DataFaker 2.x integration (~1 week, optional feature)

Java 17 unlocks DataFaker 2.x (258 providers, actively maintained vs. 1.9.0 stuck at 196 providers, abandoned April 2023). Integrate as a template helper exposing `$faker` in Velocity/Mustache/JS engines.

**Recommended approach** — expose a single `Faker` instance per engine context, not per-provider registration:

```velocity
$faker.name().firstName()        ## → "Emory"
$faker.address().city()          ## → "Brittneymouth"
$faker.internet().emailAddress() ## → "john@example.com"
```

**Files requiring changes:**

| File | Change |
|------|--------|
| `mockserver/pom.xml` | Add `net.datafaker:datafaker:2.5.4` to dependency management |
| `mockserver-core/pom.xml` | Add datafaker dependency |
| `mockserver-core/.../templates/engine/TemplateFunctions.java` | Add `faker` entry to `BUILT_IN_FUNCTIONS` |
| `mockserver-core/.../templates/engine/velocity/VelocityTemplateEngine.java` | Make `faker` available in Velocity context |
| `mockserver-core/.../templates/engine/mustache/MustacheTemplateEngine.java` | Expose `faker` in Mustache data model |
| `mockserver-core/.../templates/engine/javascript/JavaScriptTemplateEngine.java` | Bind `faker` in JS engine scope |
| `mockserver-netty/pom.xml` (shade) | Verify `net.datafaker` shading — datafaker shades its own snakeyaml internally, so no classpath conflict expected |
| Engine test files | Add faker template tests for each engine |
| `jekyll-www.mock-server.com/mock_server/response_templates.html` | Document `$faker` usage with examples |

Defer until other items land — this is a new feature, not migration cleanup.

### 5. Language modernisation (incremental backlog)

These can land file-by-file over time; not a single deliverable. Track as a long-running cleanup, not a sprint.

**Pattern matching for `instanceof`** — 279 checks across 76 files. Highest-value targets:

| File | Count |
|------|------:|
| `serialization/model/BodyDTO.java` | 26 |
| `openapi/examples/JsonNodeExampleSerializer.java` | 17 |
| `openapi/examples/ExampleBuilder.java` | 17 |
| `openapi/OpenAPIConverter.java` | 13 |
| `matchers/HttpRequestPropertiesMatcher.java` | 13 |
| `serialization/java/HttpRequestToJavaSerializer.java` | 10 |

**Text blocks** — `cli/Main.java` (34-line USAGE constant), `validator/jsonschema/JsonSchemaValidator.java`, dashboard serializer tests (thousands of lines of inline JSON concatenation).

**Switch expressions** — `HttpActionHandler.java` 12-branch switch on `Action.Type` (~90 lines) is the clearest win.

**Records** — `Delay`, `VerificationTimesDTO`, `TimesDTO`, `WebSocketClientIdDTO` are candidates. Skip any DTO that depends on Jackson no-arg constructors.

**Sealed classes** — `Body<T>` (11 subtypes), `Action<T>` (9), `RequestDefinition` (2), `BodyDTO` (11). Sealing enables exhaustive `instanceof` chains once pattern matching lands.

### 6. Review follow-ups

Adversarial review on the mega-bump commit raised four findings. Two were applied as a follow-up cleanup; two were verified as false alarms (the reviewer missed actual HC4 usage in the netty integration tests and the `BookServiceApacheHttpClient` example):

- **Applied**: `WebMvcConfiguration.java` bumped FreeMarker `Configuration(VERSION_2_3_22)` → `VERSION_2_3_32`; obsolete `@SuppressWarnings("deprecation")` removed.
- **Applied**: `jekyll-www.mock-server.com/.../response_action_code_examples.html` got a "Note for WAR deployments" caveat next to the `withReasonPhrase` example — Jakarta Servlet 6 (Tomcat 11+, Jetty 12+) removed `HttpServletResponse.setStatus(int, String)` so reason phrases are silently dropped in WAR mode. Netty / standalone / Docker deployments are unaffected.
- **Not a real issue**: root pom `<httpcomponents.version>4.4.1</httpcomponents.version>` and `httpclient:4.5.14` `dependencyManagement` entry. HC4 (`org.apache.http.*`) is still in active use by ~8 `mockserver-netty` integration test files (`AbstractClientAuthenticationMockingIntegrationTest`, `NettyHttp*ProxyIntegrationTest`, etc.) — pulled in transitively via `mockserver-integration-testing`. Removing the pin risks version drift.
- **Not a real issue**: `mockserver-examples/pom.xml` HC4 `httpclient` declaration. `BookServiceApacheHttpClient.java` exists specifically to demonstrate HC4 client usage as a third-party example, alongside the `BookServiceSpringRestTemplate.java` example that uses HC5.

## Migration checklist

- [x] Update `maven.compiler.source` and `maven.compiler.target` to `17`
- [x] Align CI / build JDK to 17
- [x] Migrate `javax.servlet` → `jakarta.servlet` (29 files)
- [x] Upgrade Spring 5.x → 7.x (skipped past 6)
- [x] Upgrade Tomcat 9.x → 11.x
- [x] Upgrade Jetty 9.x → 12.x in `mockserver-examples`
- [x] Drop Dependabot/Snyk jakarta blocklist
- [x] Rewrite AGENTS.md Java compatibility policy
- [x] Commit the working-tree jakarta migration coherently
- [x] Delete `.bak` files (`mockserver-war/pom.xml.bak`, `mockserver-proxy-war/pom.xml.bak`)
- [ ] Remove Nashorn fallback from `JavaScriptTemplateEngine` (GraalVM JS only)
- [ ] Delete `org.openjdk.nashorn:nashorn-core` + ASM entries from pom files
- [ ] Update consumer `response_templates.html` (full ES2023+ support, no Nashorn limitations)
- [ ] Re-evaluate `--add-exports=java.base/sun.security.{x509,util}=ALL-UNNAMED`
- [ ] Document ZGC (`-XX:+UseZGC`) in performance-tuning docs
- [x] WebMvcConfiguration FreeMarker version bump + drop obsolete `@SuppressWarnings("deprecation")`
- [x] Note Servlet 6 dropping custom reason phrases in WAR/servlet consumer docs (`response_action_code_examples.html`)
- HC4 `httpcomponents.version` / `httpclient:4.5.14` left as-is — still in active use by `mockserver-netty` integration tests and `BookServiceApacheHttpClient` example
- [ ] Integrate DataFaker 2.x (optional feature)
- [ ] Language modernisation (incremental — pattern matching, text blocks, switch expressions, records, sealed classes)

## Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| Spring 7 jump skips Spring 6 LTS | Low | Spring 7 requires Java 17 (delivered); 6→7 API changes are minimal for MockServer's small Spring surface area |
| Tomcat 11 jump skips Tomcat 10 LTS | Low | Both use jakarta namespace; 11 is the active line |
| Jetty 12 architectural changes | Medium | Only affects `mockserver-examples`; tests in that module run cleanly in `mvn verify` |
| GraalVM JS already paid cost | Low | No image-size change from removing Nashorn (~2.4 MB shave, immaterial) |
| `--add-exports` removal breaks TLS | Medium | Test full TLS / mTLS suite before removing; keep flags if any reference remains |
