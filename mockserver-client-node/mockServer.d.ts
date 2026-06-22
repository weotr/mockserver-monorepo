/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

/* eslint-disable */
/* tslint:disable */
/*
 * ---------------------------------------------------------------
 * ## THIS FILE WAS GENERATED VIA SWAGGER-TYPESCRIPT-API        ##
 * ##                                                           ##
 * ## AUTHOR: acacode                                           ##
 * ## SOURCE: https://github.com/acacode/swagger-typescript-api ##
 * ---------------------------------------------------------------
 */

export type Expectations = Expectation | Expectation[];

export type Expectation = {
  id?: string;
  priority?: number;
  percentage?: number;
  httpRequest?: RequestDefinition;
  openAPIDefinition?: OpenAPIDefinition;
  httpResponse?: HttpResponse;
  httpResponseTemplate?: HttpTemplate;
  httpResponseClassCallback?: HttpClassCallback;
  httpResponseObjectCallback?: HttpObjectCallback;
  httpForward?: HttpForward;
  httpForwardTemplate?: HttpTemplate;
  httpForwardClassCallback?: HttpClassCallback;
  httpForwardObjectCallback?: HttpObjectCallback;
  httpOverrideForwardedRequest?: HttpOverrideForwardedRequest;
  httpError?: HttpError;
  httpSseResponse?: HttpSseResponse;
  httpWebSocketResponse?: HttpWebSocketResponse;
  grpcStreamResponse?: GrpcStreamResponse;
  grpcBidiResponse?: GrpcBidiResponse;
  binaryResponse?: BinaryResponse;
  dnsResponse?: DnsResponse;
  times?: Times;
  timeToLive?: TimeToLive;
  chaos?: HttpChaosProfile;
  beforeActions?: AfterAction | AfterAction[];
  afterActions?: AfterAction | AfterAction[];
  httpResponses?: HttpResponse[];
  responseMode?: "SEQUENTIAL" | "RANDOM" | "WEIGHTED" | "SWITCH";
  /** index-aligned with httpResponses; relative weights used when responseMode is WEIGHTED */
  responseWeights?: number[];
  /** requests served per response block before advancing when responseMode is SWITCH (default 1) */
  switchAfter?: number;
  steps?: ExpectationStep[];
  scenarioName?: string;
  scenarioState?: string;
  newScenarioState?: string;
  /** advance a (possibly different) scenario when a non-HTTP protocol event occurs */
  crossProtocolScenarios?: CrossProtocolScenario[];
};

/**
 * Advances a named scenario to a target state when a non-HTTP protocol event
 * occurs (a DNS query, WebSocket connect, gRPC request, or HTTP request),
 * letting a single expectation drive a cross-protocol state machine.
 */
export interface CrossProtocolScenario {
  /** the protocol event that triggers the transition */
  trigger: "DNS_QUERY" | "WEBSOCKET_CONNECT" | "GRPC_REQUEST" | "HTTP_REQUEST";
  /** optional substring filter on the event identifier; omit to match all events */
  matchPattern?: string;
  /** the scenario to advance */
  scenarioName: string;
  /** the state to transition the scenario to */
  targetState: string;
}

export interface ExpectationId {
  id: string;
}

export type OpenAPIExpectations = OpenAPIExpectation | OpenAPIExpectation[];

export interface OpenAPIExpectation {
  specUrlOrPayload: string | object;
  operationsAndResponses?: Record<string, string>;
}

export type RequestDefinition = HttpRequest | OpenAPIDefinition;

export interface HttpRequest {
  secure?: boolean;
  keepAlive?: boolean;
  respondBeforeBody?: boolean;
  method?: StringOrJsonSchema;
  path?: StringOrJsonSchema;
  pathParameters?: KeyToMultiValue;
  queryStringParameters?: KeyToMultiValue;

  /** request body matcher */
  body?: Body;
  headers?: KeyToMultiValue;
  cookies?: KeyToValue;
  socketAddress?: SocketAddress;
}

export interface OpenAPIDefinition {
  specUrlOrPayload?: string;
  operationId?: string;
}

export interface HttpResponse {
  /** response delay */
  delay?: Delay;
  primary?: boolean;

  /** response body */
  body?: BodyWithContentType;
  cookies?: KeyToValue;

  /** connection options */
  connectionOptions?: ConnectionOptions;
  headers?: KeyToMultiValue;
  statusCode?: number;
  reasonPhrase?: string;
}

export interface HttpTemplate {
  /** response delay */
  delay?: Delay;
  primary?: boolean;
  templateType?: "VELOCITY" | "JAVASCRIPT" | "MUSTACHE";
  template?: string;
  templateFile?: string;
}

export interface HttpForward {
  /** response delay */
  delay?: Delay;
  primary?: boolean;
  host?: string;
  port?: number;
  scheme?: "HTTP" | "HTTPS";
}

export interface HttpClassCallback {
  /** response delay */
  delay?: Delay;
  primary?: boolean;
  callbackClass?: string;
}

export interface HttpObjectCallback {
  /** response delay */
  delay?: Delay;
  primary?: boolean;
  clientId?: string;
  responseCallback?: boolean;
}

export type HttpOverrideForwardedRequest =
  | {
      delay?: Delay;
      primary?: boolean;
      requestOverride?: HttpRequest;
      requestModifier?: {
        path?: { regex?: string; substitution?: string };
        queryStringParameters?: { add?: KeyToMultiValue; replace?: KeyToMultiValue; remove?: string[] };
        headers?: { add?: KeyToMultiValue; replace?: KeyToMultiValue; remove?: string[] };
        cookies?: { add?: KeyToValue; replace?: KeyToValue; remove?: string[] };
      };
      responseOverride?: HttpResponse;
      responseModifier?: HttpResponseModifier;
    }
  | { delay?: Delay; primary?: boolean; httpRequest?: HttpRequest; httpResponse?: HttpResponse };

export interface HttpResponseModifierCondition {
  /** apply only when the response status code equals this value */
  statusCode?: number;
  /** apply only when the response status code is in this class range, e.g. "2xx" or "5xx" */
  statusCodeRange?: string;
  /** apply only when the response has this header */
  responseHasHeader?: string;
  /** apply only when the request had this header */
  requestHasHeader?: string;
}

export interface HttpResponseModifier {
  headers?: { add?: KeyToMultiValue; replace?: KeyToMultiValue; remove?: string[] };
  cookies?: { add?: KeyToValue; replace?: KeyToValue; remove?: string[] };
  /** gate this modifier on a condition over the request/response */
  condition?: HttpResponseModifierCondition;
  /** ordered chain of modifiers, each applied to the output of the previous */
  modifiers?: HttpResponseModifier[];
  /** RFC 6902 JSON Patch document applied to a forwarded JSON response body */
  jsonPatch?: unknown[];
  /** RFC 7386 JSON Merge Patch document applied to a forwarded JSON response body */
  jsonMergePatch?: object;
}

export interface HttpError {
  /** response delay */
  delay?: Delay;
  primary?: boolean;
  /** drop the connection; ignored when streamError is set (streamError takes precedence) */
  dropConnection?: boolean;
  responseBytes?: string;
  /** reset the matched request stream with this error code (HTTP/2 RST_STREAM / HTTP/3 RESET_STREAM) instead of returning a response; HTTP/1.1 has no stream concept so this falls back to dropping the connection */
  streamError?: number;
}

export interface Times {
  remainingTimes?: number;
  unlimited?: boolean;
}

export interface TimeToLive {
  timeUnit?: "DAYS" | "HOURS" | "MINUTES" | "SECONDS" | "MILLISECONDS" | "MICROSECONDS" | "NANOSECONDS";
  timeToLive?: number;
  unlimited?: boolean;
}

export type KeyToMultiValue =
  | { name?: string; values?: string[] }[]
  | { keyMatchStyle?: "MATCHING_KEY" | "SUB_SET"; [key: string]: any };

export type KeyToValue = { name?: string; value?: string }[] | Record<string, any>;

export type StringOrJsonSchema =
  | string
  | {
      not?: boolean;
      optional?: boolean;
      value?: string;
      schema?: any;
      parameterStyle?:
        | "SIMPLE"
        | "SIMPLE_EXPLODED"
        | "LABEL"
        | "LABEL_EXPLODED"
        | "MATRIX"
        | "MATRIX_EXPLODED"
        | "FORM_EXPLODED"
        | "FORM"
        | "SPACE_DELIMITED_EXPLODED"
        | "SPACE_DELIMITED"
        | "PIPE_DELIMITED_EXPLODED"
        | "PIPE_DELIMITED"
        | "DEEP_OBJECT";
    };

export interface SocketAddress {
  host?: string;
  port?: number;
  scheme?: "HTTP" | "HTTPS";
}

/**
 * request body matcher
 */
export type Body =
  | { not?: boolean; type?: "BINARY"; base64Bytes?: string; contentType?: string }
  | { not?: boolean; type?: "JSON"; json?: string; contentType?: string; matchType?: "STRICT" | "ONLY_MATCHING_FIELDS" }
  | Record<string, any>
  | { not?: boolean; type?: "JSON_SCHEMA"; jsonSchema?: any }
  | { not?: boolean; type?: "JSON_PATH"; jsonPath?: string }
  | { not?: boolean; type?: "PARAMETERS"; parameters?: KeyToMultiValue }
  | { not?: boolean; type?: "REGEX"; regex?: string }
  | { not?: boolean; type?: "STRING"; string?: string; contentType?: string; subString?: boolean }
  | string
  | { not?: boolean; type?: "XML"; xml?: string; contentType?: string }
  | { not?: boolean; type?: "XML_SCHEMA"; xmlSchema?: string }
  | { not?: boolean; type?: "XPATH"; xpath?: string }
  | { not?: boolean; type?: "FILE"; filePath?: string; contentType?: string; templateType?: "VELOCITY" | "MUSTACHE" }
  | {
      type: 'JSON_RPC';
      method: string;
      paramsSchema?: string;
      not?: boolean;
      optional?: boolean;
    }
  | {
      type: 'GRAPHQL';
      query: string;
      operationName?: string;
      variablesSchema?: string;
      not?: boolean;
      optional?: boolean;
    }
  | ({ not?: boolean; type?: "BINARY"; base64Bytes?: string; contentType?: string } & {
      not?: boolean;
      type?: "JSON";
      json?: string;
      contentType?: string;
      matchType?: "STRICT" | "ONLY_MATCHING_FIELDS";
    } & Record<string, any> & { not?: boolean; type?: "JSON_SCHEMA"; jsonSchema?: any } & {
        not?: boolean;
        type?: "JSON_PATH";
        jsonPath?: string;
      } & { not?: boolean; type?: "PARAMETERS"; parameters?: KeyToMultiValue } & {
        not?: boolean;
        type?: "REGEX";
        regex?: string;
      } & { not?: boolean; type?: "STRING"; string?: string; contentType?: string; subString?: boolean } & {
        not?: boolean;
        type?: "XML";
        xml?: string;
        contentType?: string;
      } & { not?: boolean; type?: "XML_SCHEMA"; xmlSchema?: string } & {
        not?: boolean;
        type?: "XPATH";
        xpath?: string;
      });

/**
 * response body
 */
export type BodyWithContentType =
  | { not?: boolean; type?: "BINARY"; base64Bytes?: string; contentType?: string }
  | { not?: boolean; type?: "JSON"; json?: string; contentType?: string }
  | Record<string, any>
  | { not?: boolean; type?: "STRING"; string?: string; contentType?: string }
  | string
  | { not?: boolean; type?: "XML"; xml?: string; contentType?: string }
  | ({ not?: boolean; type?: "BINARY"; base64Bytes?: string; contentType?: string } & {
      not?: boolean;
      type?: "JSON";
      json?: string;
      contentType?: string;
    } & Record<string, any> & { not?: boolean; type?: "STRING"; string?: string; contentType?: string } & {
        not?: boolean;
        type?: "XML";
        xml?: string;
        contentType?: string;
      });

/**
 * delay distribution for variable response delays
 */
export interface DelayDistribution {
  type?: "UNIFORM" | "LOG_NORMAL" | "GAUSSIAN";
  min?: number;
  max?: number;
  median?: number;
  p99?: number;
  mean?: number;
  stdDev?: number;
}

/**
 * response delay
 */
export interface Delay {
  timeUnit?: string;
  value?: number;
  distribution?: DelayDistribution;
}

export interface SseEvent {
  event?: string;
  data?: string;
  id?: string;
  retry?: number;
  delay?: Delay;
}

export interface HttpSseResponse {
  statusCode?: number;
  headers?: KeyToMultiValue;
  events?: SseEvent[];
  closeConnection?: boolean;
  delay?: Delay;
  primary?: boolean;
}

export interface WebSocketMessage {
  text?: string;
  binary?: string;
  delay?: Delay;
}

export interface HttpWebSocketResponse {
  subprotocol?: string;
  messages?: WebSocketMessage[];
  closeConnection?: boolean;
  delay?: Delay;
  primary?: boolean;
}

export interface GrpcStreamMessage {
  json?: string;
  delay?: Delay;
}

export interface GrpcStreamResponse {
  statusName?: string;
  statusMessage?: string;
  headers?: KeyToMultiValue;
  messages?: GrpcStreamMessage[];
  closeConnection?: boolean;
  delay?: Delay;
  primary?: boolean;
}

export interface GrpcBidiRule {
  matchJson?: string;
  responses?: GrpcStreamMessage[];
}

export interface GrpcBidiResponse {
  statusName?: string;
  statusMessage?: string;
  headers?: KeyToMultiValue;
  messages?: GrpcStreamMessage[];
  rules?: GrpcBidiRule[];
  closeConnection?: boolean;
  delay?: Delay;
  primary?: boolean;
}

export interface BinaryResponse {
  /** base64-encoded binary payload */
  binaryData?: string;
  delay?: Delay;
  primary?: boolean;
}

export interface DnsRecord {
  name?: string;
  type?: "A" | "AAAA" | "CNAME" | "MX" | "SRV" | "TXT" | "PTR";
  dnsClass?: string;
  ttl?: number;
  value?: string;
  priority?: number;
  weight?: number;
  port?: number;
}

export interface DnsResponse {
  answerRecords?: DnsRecord[];
  authorityRecords?: DnsRecord[];
  additionalRecords?: DnsRecord[];
  responseCode?: "NOERROR" | "FORMERR" | "SERVFAIL" | "NXDOMAIN" | "NOTIMP" | "REFUSED";
  delay?: Delay;
  primary?: boolean;
}

export interface HttpChaosProfile {
  errorStatus?: number;
  errorProbability?: number;
  dropConnectionProbability?: number;
  retryAfter?: string;
  latency?: Delay;
  seed?: number;
  succeedFirst?: number;
  failRequestCount?: number;
  outageAfterMillis?: number;
  outageDurationMillis?: number;
  truncateBodyAtFraction?: number;
  malformedBody?: boolean;
  slowResponseChunkSize?: number;
  slowResponseChunkDelay?: Delay;
  quotaName?: string;
  quotaLimit?: number;
  quotaWindowMillis?: number;
  quotaErrorStatus?: number;
  degradationRampMillis?: number;
}

/**
 * The kind of a {@link LoadStage} in a {@link LoadProfile}.
 *
 * - `VU` — closed model: hold or ramp the number of concurrent virtual users.
 * - `RATE` — open model: hold or ramp a target arrival rate (iterations/second).
 * - `PAUSE` — drive no load for the stage's `durationMillis`.
 */
export type LoadStageType = "VU" | "RATE" | "PAUSE";

/**
 * The interpolation curve used to ramp a value (virtual users or arrival rate)
 * from a start setpoint to an end setpoint across a ramping {@link LoadStage}.
 */
export type RampCurve = "LINEAR" | "EXPONENTIAL" | "QUADRATIC";

/**
 * One stage of a {@link LoadProfile}: a contiguous slice of the run holding or
 * ramping a setpoint for `durationMillis`. Stages run in sequence.
 *
 * - `VU` stages hold `vus`, or ramp from `startVus` to `endVus` along `curve`.
 * - `RATE` stages hold `rate`, or ramp from `startRate` to `endRate`
 *   (iterations/second) along `curve`, optionally capped at `maxVus`.
 * - `PAUSE` stages drive no load and use only `durationMillis`.
 */
export interface LoadStage {
  type: LoadStageType;
  /** length of this stage in milliseconds (> 0) */
  durationMillis: number;
  /** ramp curve for ramping VU/RATE stages (defaults to LINEAR) */
  curve?: RampCurve;
  /** VU hold: the fixed number of virtual users */
  vus?: number;
  /** VU ramp: virtual users at the start of the ramp */
  startVus?: number;
  /** VU ramp: virtual users at the end of the ramp */
  endVus?: number;
  /** RATE hold: the fixed arrival rate in iterations/second */
  rate?: number;
  /** RATE ramp: arrival rate at the start of the ramp (iterations/second) */
  startRate?: number;
  /** RATE ramp: arrival rate at the end of the ramp (iterations/second) */
  endRate?: number;
  /** RATE only: optional cap on virtual users used to drive the rate */
  maxVus?: number;
}

/**
 * Traffic profile that shapes a {@link LoadScenario} over time as an ordered
 * list of {@link LoadStage}s run in sequence. Each stage holds or ramps virtual
 * users (`VU`), an arrival rate (`RATE`), or drives no load (`PAUSE`).
 */
export interface LoadProfile {
  stages: LoadStage[];
}

/**
 * A single request step within a {@link LoadScenario} iteration.
 */
export interface LoadStep {
  name?: string;
  labels?: { [key: string]: string };
  /** optional pause before issuing this step's request */
  thinkTime?: Delay;
  request: HttpRequest;
}

/**
 * A server-driven load-injection scenario. MockServer drives the `steps`
 * against the configured traffic `profile`. Register a scenario with
 * `loadScenario(...)`; run registered scenarios with `startLoadScenarios(...)`.
 * Registration is allowed at any time, but starting a scenario requires the
 * server to be started with `loadGenerationEnabled=true`.
 */
export interface LoadScenario {
  /** unique name used to address the scenario in the registry */
  name: string;
  templateType?: "VELOCITY" | "MUSTACHE";
  labels?: { [key: string]: string };
  /** optional hard cap on the total number of requests sent */
  maxRequests?: number;
  /** optional delay before the scenario begins once started */
  startDelayMillis?: number;
  profile: LoadProfile;
  steps: LoadStep[];
}

/**
 * Lifecycle state of a registered load scenario in the registry.
 *   LOADED    - registered, not yet started
 *   PENDING   - started, waiting out its startDelayMillis
 *   RUNNING   - actively driving load
 *   COMPLETED - finished naturally
 *   STOPPED   - stopped before completion
 */
export type LoadScenarioState = "LOADED" | "PENDING" | "RUNNING" | "COMPLETED" | "STOPPED";

/**
 * Live runtime status of a load scenario run (carried on a registry entry under
 * `status` while/after the scenario runs).
 */
export interface LoadScenarioStatus {
  state: string;
  name?: string;
  elapsedMillis?: number;
  currentVus?: number;
  requestsSent?: number;
  succeeded?: number;
  failed?: number;
  p50Millis?: number;
  p95Millis?: number;
  p99Millis?: number;
  runId?: string;
  startedAt?: number;
  endedAt?: number;
  labels?: { [key: string]: string };
  definition?: LoadScenario;
}

/**
 * A single entry in the load scenario registry, as returned by
 * `getLoadScenario(name)` and within `loadScenarios()`.
 */
export interface LoadScenarioEntry {
  name: string;
  state: LoadScenarioState;
  definition?: LoadScenario;
  /** present once the scenario has been started */
  status?: LoadScenarioStatus;
}

/**
 * Result of registering a load scenario via `loadScenario(...)`.
 */
export interface LoadScenarioRegistration {
  name: string;
  state: LoadScenarioState;
}

/**
 * Result of listing the registry via `loadScenarios()`.
 */
export interface LoadScenarioList {
  scenarios: LoadScenarioEntry[];
}

/**
 * Result of starting scenarios via `startLoadScenarios(...)` /
 * `runLoadScenario(...)`.
 */
export interface LoadScenarioStartResult {
  started: LoadScenarioRegistration[];
  status: string;
}

/**
 * Result of stopping scenarios via `stopLoadScenarios(...)`.
 */
export interface LoadScenarioStopResult {
  stopped: LoadScenarioRegistration[];
  status: string;
}

export interface AfterAction {
  httpRequest?: HttpRequest;
  httpClassCallback?: HttpClassCallback;
  httpObjectCallback?: HttpObjectCallback;
  delay?: Delay;
  /** before-actions only: when true (the default) the response waits for this action */
  blocking?: boolean;
  /** before-actions only: max wait for a blocking action */
  timeout?: Delay;
  /** before-actions only: outcome when a blocking action fails or times out */
  failurePolicy?: "FAIL_FAST" | "BEST_EFFORT";
}

/**
 * A single step in an ordered multi-action expectation pipeline.
 *
 * Each step carries exactly ONE action target and a {@link responder} flag.
 * Steps without `responder = true` are side-effects (fire-and-forget webhooks/callbacks).
 * Exactly one step in the list must be marked as the responder; that step's action
 * produces the HTTP response.
 */
export interface ExpectationStep {
  /** Side-effect / webhook target */
  httpRequest?: HttpRequest;
  httpClassCallback?: HttpClassCallback;
  httpObjectCallback?: HttpObjectCallback;
  /** Forward targets (usable as side-effect or responder) */
  httpForward?: HttpForward;
  httpOverrideForwardedRequest?: HttpOverrideForwardedRequest;
  /** Response targets (responder only) */
  httpResponse?: HttpResponse;
  httpError?: HttpError;
  /** When true, this step's action produces the HTTP response */
  responder?: boolean;
  delay?: Delay;
  /** Side-effect steps only: when true (the default) the pipeline waits for completion */
  blocking?: boolean;
  /** Side-effect steps only: maximum time to wait for a blocking step */
  timeout?: Delay;
  /** Side-effect steps only: what to do when a blocking step fails or times out */
  failurePolicy?: "FAIL_FAST" | "BEST_EFFORT";
}

/**
 * connection options
 */
export interface ConnectionOptions {
  suppressContentLengthHeader?: boolean;
  contentLengthHeaderOverride?: number;
  suppressConnectionHeader?: boolean;
  chunkSize?: number;
  chunkDelay?: Delay;
  keepAliveOverride?: boolean;
  closeSocket?: boolean;

  /** response delay */
  closeSocketDelay?: Delay;
}

/**
 * verification
 */
export type Verification = {
  expectationId?: ExpectationId;
  httpRequest?: RequestDefinition;
  times?: VerificationTimes;
  maximumNumberOfRequestToReturnInVerificationFailure?: number;
};

/**
 * number of request to verify
 */
export interface VerificationTimes {
  atLeast?: number;
  atMost?: number;
}

/**
 * verification sequence
 */
export type VerificationSequence = {
  expectationIds?: ExpectationId[];
  httpRequests?: RequestDefinition[];
  maximumNumberOfRequestToReturnInVerificationFailure?: number;
};

/**
 * list of ports
 */
export interface Ports {
  ports?: number[];
}

/**
 * verification sequence
 */
export type HttpRequestAndHttpResponse = {
    httpRequest?: HttpRequest;
    httpResponse?: HttpResponse;
    timestamp?: string;
};

export interface CrudExpectationsDefinition {
  basePath: string;
  idField?: string;
  idStrategy?: "AUTO_INCREMENT" | "UUID";
  initialData?: object[];
}

export type ClearUpdatePayload = RequestDefinition | ExpectationId;

export interface RetrieveUpdateParams {
  /** changes response format, default if not specificed is "json", supported values are "java", "json", "log_entries" */
  format?: "java" | "json" | "log_entries";

  /** specifies the type of object that is retrieve, default if not specified is "requests", supported values are "logs", "requests", "recorded_expectations", "active_expectations" */
  type?: "logs" | "requests" | "request_responses" | "recorded_expectations" | "active_expectations";
}
