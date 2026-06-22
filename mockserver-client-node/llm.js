/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

/*
 * LLM mocking builder API for the MockServer Node client.
 *
 * This is a browser-safe, dependency-free port of the Java client LLM
 * builders (org.mockserver.client.Llm / LlmMockBuilder / LlmConversationBuilder
 * / LlmFailoverBuilder / TurnBuilder) and their model classes (Completion,
 * ToolUse, Usage, StreamingPhysics, EmbeddingResponse). The builders produce
 * exactly the same expectation wire JSON that the Java client would, so a mock
 * scripted from Node is byte-for-byte equivalent to one scripted from Java.
 *
 * The expectation action is carried in the `httpLlmResponse` field of an
 * expectation (sibling of `httpRequest`, `scenarioName`, `scenarioState`,
 * `newScenarioState`). Null/undefined fields are omitted from the JSON, matching
 * the server's NON_NULL serialization.
 */
(function () {
    "use strict";

    // ---------------------------------------------------------------------
    // Provider enum (UPPERCASE names, matching org.mockserver.model.Provider)
    // ---------------------------------------------------------------------
    var Provider = {
        ANTHROPIC: "ANTHROPIC",
        OPENAI: "OPENAI",
        OPENAI_RESPONSES: "OPENAI_RESPONSES",
        GEMINI: "GEMINI",
        BEDROCK: "BEDROCK",
        AZURE_OPENAI: "AZURE_OPENAI",
        OLLAMA: "OLLAMA"
    };

    // Role enum (UPPERCASE, matching org.mockserver.llm.ParsedMessage.Role)
    var Role = {
        USER: "USER",
        ASSISTANT: "ASSISTANT",
        TOOL: "TOOL",
        SYSTEM: "SYSTEM"
    };

    // ---------------------------------------------------------------------
    // Minimal RFC4122 v4 UUID (no dependency) for conversation scenario names.
    // ---------------------------------------------------------------------
    function uuidv4() {
        return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
            var r = (Math.random() * 16) | 0;
            var v = c === "x" ? r : (r & 0x3) | 0x8;
            return v.toString(16);
        });
    }

    function omitNullFields(object) {
        var result = {};
        for (var key in object) {
            if (object.hasOwnProperty(key) && object[key] !== null && object[key] !== undefined) {
                result[key] = object[key];
            }
        }
        return result;
    }

    // =====================================================================
    // ToolUse
    // =====================================================================
    function ToolUse(name) {
        if (!(this instanceof ToolUse)) {
            return new ToolUse(name);
        }
        this._name = name;
        this._id = null;
        this._arguments = null;
    }

    ToolUse.prototype.withId = function (id) {
        this._id = id;
        return this;
    };

    ToolUse.prototype.withName = function (name) {
        this._name = name;
        return this;
    };

    ToolUse.prototype.withArguments = function (args) {
        // accepts a JSON string (matching the Java API) or an object (serialised to JSON)
        this._arguments = typeof args === "string" ? args : JSON.stringify(args);
        return this;
    };

    ToolUse.prototype.toJSON = function () {
        return omitNullFields({
            id: this._id,
            name: this._name,
            arguments: this._arguments
        });
    };

    function toolUse(name) {
        return new ToolUse(name);
    }

    // =====================================================================
    // Usage
    // =====================================================================
    function Usage() {
        if (!(this instanceof Usage)) {
            return new Usage();
        }
        this._inputTokens = null;
        this._outputTokens = null;
    }

    Usage.prototype.withInputTokens = function (inputTokens) {
        if (inputTokens !== null && inputTokens !== undefined && inputTokens < 0) {
            throw new Error("inputTokens must be >= 0");
        }
        this._inputTokens = inputTokens;
        return this;
    };

    Usage.prototype.withOutputTokens = function (outputTokens) {
        if (outputTokens !== null && outputTokens !== undefined && outputTokens < 0) {
            throw new Error("outputTokens must be >= 0");
        }
        this._outputTokens = outputTokens;
        return this;
    };

    Usage.prototype.toJSON = function () {
        return omitNullFields({
            inputTokens: this._inputTokens,
            outputTokens: this._outputTokens
        });
    };

    function usage() {
        return new Usage();
    }

    function inputTokens(n) {
        return new Usage().withInputTokens(n);
    }

    function outputTokens(n) {
        return new Usage().withOutputTokens(n);
    }

    // =====================================================================
    // StreamingPhysics
    //   timeToFirstToken serialises as a Delay: { timeUnit, value }
    // =====================================================================
    function StreamingPhysics() {
        if (!(this instanceof StreamingPhysics)) {
            return new StreamingPhysics();
        }
        this._timeToFirstToken = null;
        this._tokensPerSecond = null;
        this._jitter = null;
        this._seed = null;
    }

    StreamingPhysics.prototype.withTimeToFirstToken = function (value, timeUnit) {
        // value may be a Delay-shaped object {timeUnit, value} or a (value, timeUnit) pair
        if (value !== null && typeof value === "object") {
            this._timeToFirstToken = { timeUnit: value.timeUnit, value: value.value };
        } else {
            this._timeToFirstToken = { timeUnit: timeUnit || "MILLISECONDS", value: value };
        }
        return this;
    };

    StreamingPhysics.prototype.withTokensPerSecond = function (tokensPerSecond) {
        if (tokensPerSecond !== null && tokensPerSecond !== undefined && (tokensPerSecond < 1 || tokensPerSecond > 10000)) {
            throw new Error("tokensPerSecond must be between 1 and 10000");
        }
        this._tokensPerSecond = tokensPerSecond;
        return this;
    };

    StreamingPhysics.prototype.withJitter = function (jitter) {
        if (jitter !== null && jitter !== undefined && (jitter < 0.0 || jitter > 1.0)) {
            throw new Error("jitter must be between 0.0 and 1.0");
        }
        this._jitter = jitter;
        return this;
    };

    StreamingPhysics.prototype.withSeed = function (seed) {
        this._seed = seed;
        return this;
    };

    StreamingPhysics.prototype.toJSON = function () {
        return omitNullFields({
            timeToFirstToken: this._timeToFirstToken,
            tokensPerSecond: this._tokensPerSecond,
            jitter: this._jitter,
            seed: this._seed
        });
    };

    function streamingPhysics() {
        return new StreamingPhysics();
    }

    function tokensPerSecond(n) {
        return new StreamingPhysics().withTokensPerSecond(n);
    }

    function jitter(j) {
        return new StreamingPhysics().withJitter(j);
    }

    // Delay representing time-to-first-token: { timeUnit, value }
    function timeToFirstToken(value, timeUnit) {
        return { timeUnit: timeUnit || "MILLISECONDS", value: value };
    }

    // =====================================================================
    // Completion
    // =====================================================================
    function Completion() {
        if (!(this instanceof Completion)) {
            return new Completion();
        }
        this._text = null;
        this._toolCalls = null;
        this._stopReason = null;
        this._usage = null;
        this._streaming = null;
        this._streamingPhysics = null;
        this._outputSchema = null;
        this._model = null;
    }

    Completion.prototype.withText = function (text) {
        this._text = text;
        return this;
    };

    Completion.prototype.withToolCall = function (toolCall) {
        if (this._toolCalls === null) {
            this._toolCalls = [];
        }
        this._toolCalls.push(toolCall);
        return this;
    };

    Completion.prototype.withToolCalls = function (toolCalls) {
        // accepts an array or a varargs list of ToolUse
        this._toolCalls = Array.isArray(toolCalls) ? toolCalls.slice() : Array.prototype.slice.call(arguments);
        return this;
    };

    Completion.prototype.withStopReason = function (stopReason) {
        this._stopReason = stopReason;
        return this;
    };

    Completion.prototype.withUsage = function (usageValue) {
        this._usage = usageValue;
        return this;
    };

    Completion.prototype.withStreaming = function (streaming) {
        this._streaming = streaming;
        return this;
    };

    Completion.prototype.streaming = function () {
        return this.withStreaming(true);
    };

    Completion.prototype.withStreamingPhysics = function (physics) {
        this._streamingPhysics = physics;
        return this;
    };

    Completion.prototype.withOutputSchema = function (outputSchema) {
        // accepts a JSON string (matching Java) or an object (serialised to a JSON string)
        this._outputSchema = typeof outputSchema === "string" ? outputSchema : JSON.stringify(outputSchema);
        return this;
    };

    Completion.prototype.withModel = function (model) {
        this._model = model;
        return this;
    };

    Completion.prototype.toJSON = function () {
        return omitNullFields({
            text: this._text,
            toolCalls: this._toolCalls,
            stopReason: this._stopReason,
            usage: this._usage,
            streaming: this._streaming,
            streamingPhysics: this._streamingPhysics,
            outputSchema: this._outputSchema,
            model: this._model
        });
    };

    function completion() {
        return new Completion();
    }

    // =====================================================================
    // EmbeddingResponse
    // =====================================================================
    function EmbeddingResponse() {
        if (!(this instanceof EmbeddingResponse)) {
            return new EmbeddingResponse();
        }
        this._dimensions = null;
        this._deterministicFromInput = null;
        this._seed = null;
    }

    EmbeddingResponse.prototype.withDimensions = function (dimensions) {
        this._dimensions = dimensions;
        return this;
    };

    EmbeddingResponse.prototype.withDeterministicFromInput = function (deterministicFromInput) {
        this._deterministicFromInput = deterministicFromInput;
        return this;
    };

    EmbeddingResponse.prototype.withSeed = function (seed) {
        this._seed = seed;
        return this;
    };

    EmbeddingResponse.prototype.toJSON = function () {
        return omitNullFields({
            dimensions: this._dimensions,
            deterministicFromInput: this._deterministicFromInput,
            seed: this._seed
        });
    };

    function embedding() {
        return new EmbeddingResponse();
    }

    // =====================================================================
    // IsolationSource — encodes as "kind:name" (e.g. "header:x-session-id")
    // =====================================================================
    function IsolationSource(kind, name) {
        if (!name) {
            throw new Error("name must not be null or empty");
        }
        this.kind = kind;
        this.name = name;
    }

    IsolationSource.prototype.encode = function () {
        return this.kind + ":" + this.name;
    };

    function header(name) {
        return new IsolationSource("header", name);
    }

    function queryParameter(name) {
        return new IsolationSource("query_parameter", name);
    }

    function cookie(name) {
        return new IsolationSource("cookie", name);
    }

    // =====================================================================
    // Helpers to build the response action JSON and the request matcher.
    // =====================================================================
    function postMatcher(path) {
        return { method: "POST", path: path };
    }

    function buildLlmResponse(provider, model, completionValue, embeddingValue, conversationPredicates, chaos) {
        return omitNullFields({
            provider: provider,
            model: model,
            completion: completionValue,
            embedding: embeddingValue,
            conversationPredicates: conversationPredicates,
            chaos: chaos
        });
    }

    // =====================================================================
    // LlmMockBuilder — a single completion or embedding mock.
    // =====================================================================
    function LlmMockBuilder(path) {
        if (!(this instanceof LlmMockBuilder)) {
            return new LlmMockBuilder(path);
        }
        this._path = path;
        this._provider = null;
        this._model = null;
        this._completion = null;
        this._embedding = null;
    }

    LlmMockBuilder.prototype.withProvider = function (provider) {
        this._provider = provider;
        return this;
    };

    LlmMockBuilder.prototype.withModel = function (model) {
        this._model = model;
        return this;
    };

    LlmMockBuilder.prototype.respondingWith = function (response) {
        if (response instanceof EmbeddingResponse) {
            this._embedding = response;
            this._completion = null;
        } else {
            this._completion = response;
            this._embedding = null;
        }
        return this;
    };

    LlmMockBuilder.prototype.build = function () {
        return {
            httpRequest: postMatcher(this._path),
            httpLlmResponse: buildLlmResponse(this._provider, this._model, this._completion, this._embedding, null, null)
        };
    };

    LlmMockBuilder.prototype.applyTo = function (client) {
        return client.mockWithLLM(this.build());
    };

    function llmMock(path) {
        return new LlmMockBuilder(path);
    }

    // =====================================================================
    // TurnBuilder — one turn within a conversation.
    // =====================================================================
    function TurnBuilder(parent) {
        this._parent = parent;
        this.turnIndex = null;
        this.latestMessageContains = null;
        this.latestMessageMatches = null;
        this.latestMessageRole = null;
        this.containsToolResultFor = null;
        this.semanticMatchAgainst = null;
        this.normalization = null;
        this.chaos = null;
        this.completion = null;
    }

    TurnBuilder.prototype.whenTurnIndex = function (n) {
        this.turnIndex = n;
        return this;
    };

    TurnBuilder.prototype.whenLatestMessageContains = function (text) {
        this.latestMessageContains = text;
        return this;
    };

    TurnBuilder.prototype.whenLatestMessageMatches = function (regex) {
        if (regex === null || regex === undefined) {
            throw new Error("regex must not be null");
        }
        this.latestMessageMatches = regex instanceof RegExp ? regex.source : regex;
        return this;
    };

    TurnBuilder.prototype.whenLatestMessageRole = function (role) {
        this.latestMessageRole = role;
        return this;
    };

    TurnBuilder.prototype.whenContainsToolResultFor = function (toolName) {
        this.containsToolResultFor = toolName;
        return this;
    };

    TurnBuilder.prototype.whenSemanticMatch = function (expectedMeaning) {
        this.semanticMatchAgainst = expectedMeaning;
        return this;
    };

    TurnBuilder.prototype.withNormalization = function (normalization) {
        this.normalization = normalization;
        return this;
    };

    TurnBuilder.prototype.withChaos = function (chaos) {
        this.chaos = chaos;
        return this;
    };

    TurnBuilder.prototype.respondingWith = function (completionValue) {
        this.completion = completionValue;
        return this;
    };

    TurnBuilder.prototype.turn = function () {
        return this._parent.turn();
    };

    TurnBuilder.prototype.andThen = function () {
        return this._parent;
    };

    TurnBuilder.prototype.build = function () {
        return this._parent.build();
    };

    TurnBuilder.prototype.applyTo = function (client) {
        return this._parent.applyTo(client);
    };

    TurnBuilder.prototype._predicates = function () {
        var predicates = omitNullFields({
            turnIndex: this.turnIndex,
            latestMessageContains: this.latestMessageContains,
            latestMessageMatches: this.latestMessageMatches,
            latestMessageRole: this.latestMessageRole,
            containsToolResultFor: this.containsToolResultFor,
            semanticMatchAgainst: this.semanticMatchAgainst,
            normalization: this.normalization
        });
        return predicates;
    };

    // hasAnyPredicate intentionally excludes `normalization` (a modifier, not a predicate)
    TurnBuilder.prototype._hasAnyPredicate = function () {
        return this.turnIndex !== null ||
            this.latestMessageContains !== null ||
            this.latestMessageMatches !== null ||
            this.latestMessageRole !== null ||
            this.containsToolResultFor !== null ||
            this.semanticMatchAgainst !== null;
    };

    // =====================================================================
    // LlmConversationBuilder — multi-turn conversation with scenario state.
    // =====================================================================
    var SCENARIO_PREFIX = "__llm_conv_";
    var ISOLATION_MARKER = "__iso=";
    var DONE_STATE = "__done";

    function LlmConversationBuilder() {
        if (!(this instanceof LlmConversationBuilder)) {
            return new LlmConversationBuilder();
        }
        this._path = null;
        this._provider = null;
        this._model = null;
        this._isolationSource = null;
        this._turns = [];
    }

    LlmConversationBuilder.prototype.withPath = function (path) {
        this._path = path;
        return this;
    };

    LlmConversationBuilder.prototype.withProvider = function (provider) {
        this._provider = provider;
        return this;
    };

    LlmConversationBuilder.prototype.withModel = function (model) {
        this._model = model;
        return this;
    };

    LlmConversationBuilder.prototype.isolateBy = function (source) {
        this._isolationSource = source;
        return this;
    };

    LlmConversationBuilder.prototype.turn = function () {
        var turnBuilder = new TurnBuilder(this);
        this._turns.push(turnBuilder);
        return turnBuilder;
    };

    LlmConversationBuilder.prototype.build = function () {
        if (this._turns.length === 0) {
            throw new Error("At least one turn must be defined");
        }
        if (!this._path) {
            throw new Error("Path must be set");
        }
        if (!this._provider) {
            throw new Error("Provider must be set");
        }

        var conversationId = SCENARIO_PREFIX + uuidv4();
        var scenarioName = conversationId;
        if (this._isolationSource) {
            scenarioName = conversationId + ISOLATION_MARKER + this._isolationSource.encode();
        }

        var expectations = [];
        for (var i = 0; i < this._turns.length; i++) {
            var turn = this._turns[i];
            var nextState = (i < this._turns.length - 1) ? "turn_" + (i + 1) : DONE_STATE;

            var llmResponse = buildLlmResponse(
                this._provider,
                this._model,
                turn.completion,
                null,
                turn._hasAnyPredicate() ? turn._predicates() : null,
                turn.chaos
            );

            expectations.push({
                httpRequest: postMatcher(this._path),
                scenarioName: scenarioName,
                scenarioState: i === 0 ? "Started" : "turn_" + i,
                newScenarioState: nextState,
                httpLlmResponse: llmResponse
            });
        }
        return expectations;
    };

    LlmConversationBuilder.prototype.applyTo = function (client) {
        return client.mockWithLLM(this.build());
    };

    function conversation() {
        return new LlmConversationBuilder();
    }

    // =====================================================================
    // LlmFailoverBuilder — N failures then a success completion.
    // =====================================================================
    function defaultErrorBody(statusCode) {
        var type;
        var message;
        switch (statusCode) {
            case 429:
                type = "rate_limit_error";
                message = "Rate limit exceeded. Please retry after a brief wait.";
                break;
            case 500:
                type = "internal_server_error";
                message = "An internal error occurred. Please retry your request.";
                break;
            case 502:
                type = "bad_gateway";
                message = "Bad gateway. The upstream server returned an invalid response.";
                break;
            case 503:
                type = "service_unavailable";
                message = "The service is temporarily overloaded. Please retry later.";
                break;
            default:
                type = "error";
                message = "Request failed with status " + statusCode;
                break;
        }
        return JSON.stringify({ error: { type: type, message: message } });
    }

    function validateStatusCode(statusCode) {
        if (statusCode < 100 || statusCode > 599) {
            throw new Error("statusCode must be between 100 and 599, got " + statusCode);
        }
    }

    function LlmFailoverBuilder() {
        if (!(this instanceof LlmFailoverBuilder)) {
            return new LlmFailoverBuilder();
        }
        this._path = null;
        this._provider = null;
        this._model = null;
        this._failures = [];
        this._successCompletion = null;
    }

    LlmFailoverBuilder.prototype.withPath = function (path) {
        this._path = path;
        return this;
    };

    LlmFailoverBuilder.prototype.withProvider = function (provider) {
        this._provider = provider;
        return this;
    };

    LlmFailoverBuilder.prototype.withModel = function (model) {
        this._model = model;
        return this;
    };

    // failWith(statusCode) | failWith(statusCode, errorBody) | failWith(statusCode, count)
    LlmFailoverBuilder.prototype.failWith = function (statusCode, second) {
        validateStatusCode(statusCode);
        if (typeof second === "number") {
            if (second < 1) {
                throw new Error("count must be >= 1, got " + second);
            }
            for (var i = 0; i < second; i++) {
                this._failures.push({ statusCode: statusCode, errorBody: null });
            }
        } else {
            this._failures.push({ statusCode: statusCode, errorBody: typeof second === "string" ? second : null });
        }
        return this;
    };

    LlmFailoverBuilder.prototype.thenRespondWith = function (completionValue) {
        this._successCompletion = completionValue;
        return this;
    };

    LlmFailoverBuilder.prototype.getFailureCount = function () {
        return this._failures.length;
    };

    LlmFailoverBuilder.prototype._coalesceFailures = function () {
        var result = [];
        for (var i = 0; i < this._failures.length; i++) {
            var spec = this._failures[i];
            if (result.length > 0) {
                var last = result[result.length - 1];
                if (last.statusCode === spec.statusCode && last.errorBody === spec.errorBody) {
                    last.count++;
                    continue;
                }
            }
            result.push({ statusCode: spec.statusCode, errorBody: spec.errorBody, count: 1 });
        }
        return result;
    };

    LlmFailoverBuilder.prototype.build = function () {
        if (!this._path) {
            throw new Error("Path must be set");
        }
        if (!this._provider) {
            throw new Error("Provider must be set");
        }
        if (this._failures.length === 0) {
            throw new Error("At least one failure must be defined");
        }
        if (this._successCompletion === null) {
            throw new Error("Success completion must be set via thenRespondWith()");
        }

        var coalesced = this._coalesceFailures();
        var expectations = [];

        for (var i = 0; i < coalesced.length; i++) {
            var cf = coalesced[i];
            var body = cf.errorBody !== null ? cf.errorBody : defaultErrorBody(cf.statusCode);
            expectations.push({
                httpRequest: postMatcher(this._path),
                times: { remainingTimes: cf.count, unlimited: false },
                timeToLive: { unlimited: true },
                httpResponse: {
                    statusCode: cf.statusCode,
                    headers: [{ name: "Content-Type", values: ["application/json"] }],
                    body: body
                }
            });
        }

        expectations.push({
            httpRequest: postMatcher(this._path),
            times: { remainingTimes: 0, unlimited: true },
            timeToLive: { unlimited: true },
            httpLlmResponse: buildLlmResponse(this._provider, this._model, this._successCompletion, null, null, null)
        });

        return expectations;
    };

    LlmFailoverBuilder.prototype.applyTo = function (client) {
        return client.mockWithLLM(this.build());
    };

    function llmFailover() {
        return new LlmFailoverBuilder();
    }

    // =====================================================================
    // Public API surface (mirrors org.mockserver.client.Llm + builders)
    // =====================================================================
    var llm = {
        // enums
        Provider: Provider,
        Role: Role,
        // entry builders
        llmMock: llmMock,
        conversation: conversation,
        llmFailover: llmFailover,
        // model factories
        completion: completion,
        toolUse: toolUse,
        usage: usage,
        inputTokens: inputTokens,
        outputTokens: outputTokens,
        streamingPhysics: streamingPhysics,
        tokensPerSecond: tokensPerSecond,
        jitter: jitter,
        timeToFirstToken: timeToFirstToken,
        embedding: embedding,
        // isolation sources
        header: header,
        queryParameter: queryParameter,
        cookie: cookie,
        // constructors (for instanceof / advanced use)
        Completion: Completion,
        ToolUse: ToolUse,
        Usage: Usage,
        StreamingPhysics: StreamingPhysics,
        EmbeddingResponse: EmbeddingResponse,
        LlmMockBuilder: LlmMockBuilder,
        LlmConversationBuilder: LlmConversationBuilder,
        LlmFailoverBuilder: LlmFailoverBuilder,
        TurnBuilder: TurnBuilder,
        IsolationSource: IsolationSource,
        // exposed for testing / parity with Java helpers
        defaultErrorBody: defaultErrorBody
    };

    if (typeof module !== "undefined") {
        module.exports = llm;
    }
    if (typeof window !== "undefined") {
        window.mockServerLlm = llm;
    }
})();
