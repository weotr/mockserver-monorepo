/*
 * mockserver
 * http://mock-server.com
 *
 * Original definitions by: David Tanner <https://github.com/DavidTanner>
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

import {BinaryResponse, DnsResponse, Expectation, ExpectationId, GrpcStreamResponse, HttpChaosProfile, HttpClassCallback, HttpRequest, HttpRequestAndHttpResponse, HttpResponse, HttpSseResponse, HttpWebSocketResponse, KeyToMultiValue, LoadScenario, LoadScenarioStatus, LoadScenarioEntry, LoadScenarioList, LoadScenarioRegistration, LoadScenarioStartResult, LoadScenarioStopResult, OpenAPIExpectation, RequestDefinition, Times, TimeToLive,} from './mockServer';
import {Llm, LlmConversationBuilder, LlmFailoverBuilder, LlmMockBuilder} from './llm';
import {McpMockBuilder} from './mcpMockBuilder';

export type Host = string;
export type Port = number;
export type ContextPath = string;
export type TLS = boolean;
export type CaCertPemFilePath = string;

/**
 * Optional control-plane authentication and mutual-TLS settings, supplied as a
 * trailing argument to mockServerClient(...). All fields are optional and
 * additive — omitting the object preserves the default (unauthenticated, no
 * client certificate) behaviour.
 */
export interface MockServerClientOptions {
    /**
     * Static control-plane JWT. When set, every control-plane request carries
     * an `Authorization: Bearer <bearerToken>` header. Use this when the server
     * is started with `controlPlaneJWTAuthenticationRequired=true`.
     */
    bearerToken?: string;

    /**
     * A supplier evaluated per control-plane request to obtain the bearer token
     * (so the token can be refreshed). Takes precedence over `bearerToken`.
     */
    bearerTokenSupplier?: () => string;

    /**
     * Path to a PEM client certificate presented for mutual TLS, for when the
     * server requires `controlPlaneTLSMutualAuthenticationRequired=true`.
     */
    clientCertPemFilePath?: string;

    /**
     * Path to the PEM private key paired with `clientCertPemFilePath` for
     * mutual TLS.
     */
    clientKeyPemFilePath?: string;
}
// Retains backwards compatability.
export type KeysToMultiValues = KeyToMultiValue;

export type ClearType = 'EXPECTATIONS' | 'LOG' | 'ALL';
export type CodeFormat = 'java' | 'javascript' | 'python' | 'go' | 'csharp' | 'ruby' | 'rust' | 'php'
    | 'JAVA' | 'JAVASCRIPT' | 'PYTHON' | 'GO' | 'CSHARP' | 'RUBY' | 'RUST' | 'PHP';

export interface ClockStatus {
    currentInstant: string;
    currentEpochMillis: number;
    frozen: boolean;
}

export interface SuccessFullRequest {
    statusCode: number;
    body: string;
}

export interface GrpcMethod {
    name: string;
    inputType: string;
    outputType: string;
    clientStreaming: boolean;
    serverStreaming: boolean;
}

export interface GrpcService {
    name: string;
    methods: GrpcMethod[];
}

/**
 * The current state of a single scenario, as returned by
 * client.scenario(name).state(), .set(...) and .trigger(...).
 */
export interface ScenarioState {
    scenarioName: string;
    currentState: string;
    /** present only when .set(...) scheduled a timed transition */
    nextState?: string;
    /** present only when .set(...) scheduled a timed transition */
    transitionAfterMs?: number;
}

/**
 * The list of all known scenarios and their current states, as returned by
 * client.scenarios().
 */
export interface ScenarioList {
    scenarios: Array<{ scenarioName: string; currentState: string }>;
}

/**
 * Options for client.scenario(name).set(state, options) — schedule a timed
 * transition to nextState after transitionAfterMs milliseconds.
 */
export interface ScenarioSetOptions {
    transitionAfterMs?: number;
    nextState?: string;
}

/**
 * A handle to a single named scenario's state machine. Returned by
 * client.scenario(name); wraps the /mockserver/scenario REST endpoints.
 */
export interface ScenarioHandle {
    /** GET /mockserver/scenario/{name} — the scenario's current state */
    state(): Promise<ScenarioState>;

    /**
     * PUT /mockserver/scenario/{name} — set the scenario's state, optionally
     * scheduling a timed transition to options.nextState after
     * options.transitionAfterMs milliseconds.
     */
    set(state: string, options?: ScenarioSetOptions): Promise<ScenarioState>;

    /** PUT /mockserver/scenario/{name}/trigger — set the state to newState immediately */
    trigger(newState: string): Promise<ScenarioState>;
}

export type RequestResponse = SuccessFullRequest | string;

export type PathOrRequestDefinition = string | Expectation | RequestDefinition | undefined | null;

export interface MockServerClient {
    openAPIExpectation(expectation: OpenAPIExpectation): Promise<RequestResponse>;

    mockAnyResponse(expectation: Expectation | Expectation[]): Promise<RequestResponse>;

    /**
     * LLM mocking builder factories (mirrors the Java client's Llm helpers).
     * Use to build completion/chat, tool-use, streaming, embedding, multi-turn
     * conversation, and provider-failover mocks, then pass the builder (or the
     * built expectation) to mockWithLLM().
     */
    llm: Llm;

    /**
     * Register one or more LLM mock expectations. Accepts a single expectation,
     * an array of expectations, or an LLM builder (the result of
     * client.llm.llmMock(...), .conversation(), or .llmFailover()) — builders are
     * built via their build() method.
     */
    mockWithLLM(expectationOrBuilder: Expectation | Expectation[] | LlmMockBuilder | LlmConversationBuilder | LlmFailoverBuilder): Promise<RequestResponse>;

    mockWithCallback(requestMatcher: RequestDefinition, requestHandler: (request: HttpRequest) => HttpResponse, times?: Times | number, priority?: number, timeToLive?: TimeToLive, id?: string): Promise<RequestResponse>;

    mockWithForwardCallback(requestMatcher: RequestDefinition, forwardHandler: (request: HttpRequest) => HttpRequest, times?: Times | number, priority?: number, timeToLive?: TimeToLive, id?: string): Promise<RequestResponse>;

    mockWithForwardAndResponseCallback(requestMatcher: RequestDefinition, forwardHandler: (request: HttpRequest) => HttpRequest, responseHandler: (request: HttpRequest, response: HttpResponse) => HttpResponse, times?: Times | number, priority?: number, timeToLive?: TimeToLive, id?: string): Promise<RequestResponse>;

    /**
     * Register an expectation that delegates the response to a server-side class
     * implementing the MockServer ExpectationResponseCallback interface (a "class
     * callback"). Pure JSON / REST-only — no callback WebSocket is opened. The
     * referenced class runs inside the MockServer JVM and must be on its classpath.
     *
     * @param requestMatcher the path to match (string) or a full request matcher object
     * @param callbackClass  the fully-qualified server-side callback class name, or a full
     *                       httpResponseClassCallback action object ({ callbackClass, delay?, primary? })
     */
    respondWithClassCallback(requestMatcher: string | RequestDefinition, callbackClass: string | HttpClassCallback, times?: Times | number, priority?: number, timeToLive?: TimeToLive, id?: string): Promise<RequestResponse>;

    /**
     * Register an expectation that delegates request forwarding to a server-side
     * class implementing the MockServer ExpectationForwardCallback interface (a
     * forward "class callback"). Pure JSON / REST-only; the referenced class must
     * be on the MockServer classpath.
     *
     * @param requestMatcher the path to match (string) or a full request matcher object
     * @param callbackClass  the fully-qualified server-side callback class name, or a full
     *                       httpForwardClassCallback action object ({ callbackClass, delay?, primary? })
     */
    forwardWithClassCallback(requestMatcher: string | RequestDefinition, callbackClass: string | HttpClassCallback, times?: Times | number, priority?: number, timeToLive?: TimeToLive, id?: string): Promise<RequestResponse>;

    mockSimpleResponse<T = any>(path: string, responseBody: T, statusCode?: number): Promise<RequestResponse>;

    respondWithSse(requestMatcher: string | RequestDefinition, sseResponse: HttpSseResponse, times?: Times | number, priority?: number, timeToLive?: TimeToLive, id?: string): Promise<RequestResponse>;

    respondWithWebSocket(requestMatcher: string | RequestDefinition, webSocketResponse: HttpWebSocketResponse, times?: Times | number, priority?: number, timeToLive?: TimeToLive, id?: string): Promise<RequestResponse>;

    respondWithDns(requestMatcher: string | RequestDefinition, dnsResponse: DnsResponse, times?: Times | number, priority?: number, timeToLive?: TimeToLive, id?: string): Promise<RequestResponse>;

    respondWithBinary(requestMatcher: string | RequestDefinition, binaryResponse: BinaryResponse, times?: Times | number, priority?: number, timeToLive?: TimeToLive, id?: string): Promise<RequestResponse>;

    respondWithGrpcStream(requestMatcher: string | RequestDefinition, grpcStreamResponse: GrpcStreamResponse, times?: Times | number, priority?: number, timeToLive?: TimeToLive, id?: string): Promise<RequestResponse>;

    setDefaultHeaders(responseHeaders: KeysToMultiValues, requestHeaders: KeysToMultiValues): MockServerClient;

    verify(matcher: RequestDefinition, atLeast?: number, atMost?: number): Promise<void | string>;

    verifyResponse(responseMatcher: HttpResponse, atLeast?: number, atMost?: number): Promise<void | string>;

    verifyRequestAndResponse(requestMatcher: RequestDefinition, responseMatcher: HttpResponse, atLeast?: number, atMost?: number): Promise<void | string>;

    verifyById(expectationId: ExpectationId, atLeast?: number, atMost?: number): Promise<void | string>;

    verifySequence(...matchers: RequestDefinition[]): Promise<void | string>;

    verifySequenceWithResponses(requestsAndResponses: Array<{request: RequestDefinition, response: HttpResponse}>): Promise<void | string>;

    verifySequenceById(...expectationIds: ExpectationId[]): Promise<void | string>;

    verifyZeroInteractions(): Promise<void | string>;

    reset(): Promise<RequestResponse>;

    clear(pathOrRequestDefinition: PathOrRequestDefinition, type: ClearType): Promise<RequestResponse>;

    clearById(expectationId: string, type: ClearType): Promise<RequestResponse>;

    freezeClock(instant?: string): Promise<RequestResponse>;

    advanceClock(durationMillis: number): Promise<RequestResponse>;

    resetClock(): Promise<RequestResponse>;

    clockStatus(): Promise<ClockStatus>;

    setServiceChaos(host: string, chaos: HttpChaosProfile, ttlMillis?: number): Promise<RequestResponse>;

    removeServiceChaos(host: string): Promise<RequestResponse>;

    clearServiceChaos(): Promise<RequestResponse>;

    serviceChaosStatus(): Promise<{ services: { [host: string]: HttpChaosProfile } }>;

    loadScenario(scenario: LoadScenario): Promise<LoadScenarioRegistration>;

    loadScenarios(): Promise<LoadScenarioList>;

    getLoadScenario(name: string): Promise<LoadScenarioEntry>;

    deleteLoadScenario(name: string): Promise<RequestResponse>;

    clearLoadScenarios(): Promise<RequestResponse>;

    startLoadScenarios(names: string | string[]): Promise<LoadScenarioStartResult>;

    stopLoadScenarios(names?: string | string[]): Promise<LoadScenarioStopResult>;

    runLoadScenario(scenario: LoadScenario): Promise<LoadScenarioStartResult>;

    /**
     * Obtain a handle to a named stateful scenario, exposing typed helpers over
     * the /mockserver/scenario REST endpoints to read (.state()), set (.set())
     * and externally trigger (.trigger()) the scenario's state.
     *
     * @param name the scenario name
     */
    scenario(name: string): ScenarioHandle;

    /**
     * List every known scenario and its current state
     * (GET /mockserver/scenario).
     */
    scenarios(): Promise<ScenarioList>;

    bind(ports: Port[]): Promise<RequestResponse>;

    retrieveRecordedRequests(pathOrRequestDefinition: PathOrRequestDefinition): Promise<HttpResponse[]>;

    retrieveRecordedRequestsAndResponses(pathOrRequestDefinition: PathOrRequestDefinition): Promise<HttpRequestAndHttpResponse[]>;

    retrieveActiveExpectations(pathOrRequestDefinition: PathOrRequestDefinition): Promise<Expectation[]>;

    retrieveRecordedExpectations(pathOrRequestDefinition: PathOrRequestDefinition): Promise<Expectation[]>;

    retrieveExpectationsAsCode(format: CodeFormat, pathOrRequestDefinition?: PathOrRequestDefinition): Promise<string>;

    retrieveRecordedExpectationsAsCode(format: CodeFormat, pathOrRequestDefinition?: PathOrRequestDefinition): Promise<string>;

    retrieveLogMessages(pathOrRequestDefinition: PathOrRequestDefinition): Promise<string[]>;

    /**
     * Register a breakpoint matcher with per-phase handlers.
     * The callback WebSocket is opened lazily on the first call and reused.
     *
     * @param requestMatcher  the request definition to match (same shape as an expectation matcher)
     * @param phases          array of phases: "REQUEST", "RESPONSE", "RESPONSE_STREAM", "INBOUND_STREAM"
     * @param requestHandler  handler for REQUEST phase: (request) => request (continue/modify) or response (abort)
     * @param responseHandler handler for RESPONSE phase: (request, response) => response
     * @param streamFrameHandler handler for streaming phases: (pausedFrame) => {correlationId, action, body?}
     * @return promise resolving to the server-assigned breakpoint matcher id (string)
     */
    addBreakpoint(
        requestMatcher: RequestDefinition,
        phases: string[],
        requestHandler?: ((request: HttpRequest) => HttpRequest | HttpResponse) | null,
        responseHandler?: ((request: HttpRequest, response: HttpResponse) => HttpResponse) | null,
        streamFrameHandler?: ((pausedFrame: any) => any) | null,
    ): Promise<string>;

    /**
     * Register a request-only breakpoint (convenience).
     */
    addRequestBreakpoint(
        requestMatcher: RequestDefinition,
        requestHandler: (request: HttpRequest) => HttpRequest | HttpResponse,
    ): Promise<string>;

    /**
     * Register a request+response breakpoint (convenience).
     */
    addRequestAndResponseBreakpoint(
        requestMatcher: RequestDefinition,
        requestHandler: (request: HttpRequest) => HttpRequest | HttpResponse,
        responseHandler: (request: HttpRequest, response: HttpResponse) => HttpResponse,
    ): Promise<string>;

    /**
     * List all registered breakpoint matchers.
     */
    listBreakpointMatchers(): Promise<{ matchers: any[] }>;

    /**
     * Remove a breakpoint matcher by id.
     */
    removeBreakpointMatcher(breakpointId: string): Promise<RequestResponse>;

    /**
     * Clear all registered breakpoint matchers.
     */
    clearBreakpointMatchers(): Promise<RequestResponse>;

    /**
     * Upload a compiled gRPC proto descriptor set (a FileDescriptorSet, as
     * produced by `protoc --descriptor_set_out`). Registered services then
     * become available for gRPC mocking and can be queried with
     * retrieveGrpcServices().
     *
     * @param descriptorSetBytes the raw bytes of the compiled descriptor set
     */
    uploadGrpcDescriptor(descriptorSetBytes: Buffer | Uint8Array | ArrayBuffer): Promise<RequestResponse>;

    /**
     * Retrieve the gRPC services registered from uploaded descriptor sets.
     */
    retrieveGrpcServices(): Promise<GrpcService[]>;

    /**
     * Clear all registered gRPC descriptor sets and services.
     */
    clearGrpcDescriptors(): Promise<RequestResponse>;

    /**
     * Start building a mock MCP (Model Context Protocol) server that speaks
     * JSON-RPC 2.0 over the Streamable HTTP transport. Returns a fluent
     * builder; call .applyTo() (no argument — this client is used) to register
     * the generated expectations, or .build() for the raw expectation array.
     *
     * @param path the HTTP path the MCP server is mounted on (default "/mcp")
     */
    mcpMock(path?: string): McpMockBuilder;

    /**
     * Explicit resource management support (TC39 `await using`). Resets the
     * MockServer when the client goes out of scope, so tests do not need a
     * manual `afterEach(() => client.reset())`.
     *
     * Present only on runtimes that define `Symbol.asyncDispose`.
     */
    [Symbol.asyncDispose]?(): Promise<RequestResponse>;

    /**
     * Synchronous explicit resource management support (TC39 `using`). Fires a
     * best-effort reset without awaiting it; prefer `await using` when the
     * reset must complete before the next test.
     *
     * Present only on runtimes that define `Symbol.dispose`.
     */
    [Symbol.dispose]?(): void;
}

/**
 * Start the client communicating at the specified host and port
 * for example:
 *
 *   var client = mockServerClient("localhost", 1080);
 *
 * @param host {string} the host for the server to communicate with
 * @param port {number} the port for the server to communicate with
 * @param contextPath {string} the context path if server was deployed as a war
 * @param tls {boolean} enable TLS (i.e. HTTPS) for communication to server
 * @param caCertPemFilePath {string} provide custom CA Certificate (defaults to MockServer CA Certificate)
 * @param options {MockServerClientOptions} optional control-plane bearer token and mutual-TLS client certificate/key
 */
export declare function mockServerClient(
    host: Host,
    port: Port,
    contextPath?: ContextPath,
    tls?: TLS,
    caCertPemFilePath?: CaCertPemFilePath,
    options?: MockServerClientOptions
): MockServerClient;

