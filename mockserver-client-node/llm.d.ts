/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

import { Expectation } from './mockServer';

/** LLM provider whose wire format the mocked response is encoded in. */
export type Provider =
    | 'ANTHROPIC'
    | 'OPENAI'
    | 'OPENAI_RESPONSES'
    | 'GEMINI'
    | 'BEDROCK'
    | 'AZURE_OPENAI'
    | 'OLLAMA';

/** Role of the latest parsed message, used by conversation predicates. */
export type Role = 'USER' | 'ASSISTANT' | 'TOOL' | 'SYSTEM';

/** A delay expressed as a time unit + magnitude (matches the server Delay shape). */
export interface Delay {
    timeUnit: string;
    value: number;
}

/** A single tool/function call the assistant should emit. */
export interface ToolUse {
    withId(id: string): ToolUse;
    withName(name: string): ToolUse;
    /** Accepts a JSON string (as in the Java API) or an object (serialised to JSON). */
    withArguments(args: string | object): ToolUse;
    toJSON(): object;
}

/** Token accounting reported on a completion. */
export interface Usage {
    withInputTokens(inputTokens: number): Usage;
    withOutputTokens(outputTokens: number): Usage;
    toJSON(): object;
}

/** Streaming timing physics: time-to-first-token, throughput, jitter, seed. */
export interface StreamingPhysics {
    withTimeToFirstToken(delay: Delay): StreamingPhysics;
    withTimeToFirstToken(value: number, timeUnit?: string): StreamingPhysics;
    withTokensPerSecond(tokensPerSecond: number): StreamingPhysics;
    withJitter(jitter: number): StreamingPhysics;
    withSeed(seed: number): StreamingPhysics;
    toJSON(): object;
}

/** A mocked LLM completion (chat/completion response). */
export interface Completion {
    withText(text: string): Completion;
    withToolCall(toolCall: ToolUse): Completion;
    withToolCalls(toolCalls: ToolUse[]): Completion;
    withToolCalls(...toolCalls: ToolUse[]): Completion;
    withStopReason(stopReason: string): Completion;
    withUsage(usage: Usage): Completion;
    withStreaming(streaming: boolean): Completion;
    streaming(): Completion;
    withStreamingPhysics(physics: StreamingPhysics): Completion;
    /** Accepts a JSON-schema string (as in the Java API) or an object. */
    withOutputSchema(outputSchema: string | object): Completion;
    withModel(model: string): Completion;
    toJSON(): object;
}

/** A mocked embedding response. */
export interface EmbeddingResponse {
    withDimensions(dimensions: number): EmbeddingResponse;
    withDeterministicFromInput(deterministicFromInput: boolean): EmbeddingResponse;
    withSeed(seed: number): EmbeddingResponse;
    toJSON(): object;
}

/** Describes where the per-session isolation key is read from. */
export interface IsolationSource {
    kind: string;
    name: string;
    encode(): string;
}

/** Builder for a single completion or embedding mock. */
export interface LlmMockBuilder {
    withProvider(provider: Provider): LlmMockBuilder;
    withModel(model: string): LlmMockBuilder;
    respondingWith(response: Completion | EmbeddingResponse): LlmMockBuilder;
    build(): Expectation;
    applyTo(client: { mockWithLLM(expectation: Expectation): unknown }): unknown;
}

/** Builder for a single turn within a multi-turn conversation. */
export interface TurnBuilder {
    whenTurnIndex(n: number): TurnBuilder;
    whenLatestMessageContains(text: string): TurnBuilder;
    whenLatestMessageMatches(regex: RegExp | string): TurnBuilder;
    whenLatestMessageRole(role: Role): TurnBuilder;
    whenContainsToolResultFor(toolName: string): TurnBuilder;
    whenSemanticMatch(expectedMeaning: string): TurnBuilder;
    withNormalization(normalization: object): TurnBuilder;
    withChaos(chaos: object): TurnBuilder;
    respondingWith(completion: Completion): TurnBuilder;
    turn(): TurnBuilder;
    andThen(): LlmConversationBuilder;
    build(): Expectation[];
    applyTo(client: { mockWithLLM(expectations: Expectation[]): unknown }): unknown;
}

/** Builder for a multi-turn conversation with scenario-based state advancement. */
export interface LlmConversationBuilder {
    withPath(path: string): LlmConversationBuilder;
    withProvider(provider: Provider): LlmConversationBuilder;
    withModel(model: string): LlmConversationBuilder;
    isolateBy(source: IsolationSource): LlmConversationBuilder;
    turn(): TurnBuilder;
    build(): Expectation[];
    applyTo(client: { mockWithLLM(expectations: Expectation[]): unknown }): unknown;
}

/** Builder for provider failover/retry scenarios. */
export interface LlmFailoverBuilder {
    withPath(path: string): LlmFailoverBuilder;
    withProvider(provider: Provider): LlmFailoverBuilder;
    withModel(model: string): LlmFailoverBuilder;
    failWith(statusCode: number): LlmFailoverBuilder;
    failWith(statusCode: number, errorBody: string): LlmFailoverBuilder;
    failWith(statusCode: number, count: number): LlmFailoverBuilder;
    thenRespondWith(completion: Completion): LlmFailoverBuilder;
    getFailureCount(): number;
    build(): Expectation[];
    applyTo(client: { mockWithLLM(expectations: Expectation[]): unknown }): unknown;
}

/** The LLM mocking builder factory namespace. */
export interface Llm {
    Provider: { [K in Provider]: K };
    Role: { [K in Role]: K };

    llmMock(path: string): LlmMockBuilder;
    conversation(): LlmConversationBuilder;
    llmFailover(): LlmFailoverBuilder;

    completion(): Completion;
    toolUse(name: string): ToolUse;
    usage(): Usage;
    inputTokens(n: number): Usage;
    outputTokens(n: number): Usage;
    streamingPhysics(): StreamingPhysics;
    tokensPerSecond(n: number): StreamingPhysics;
    jitter(j: number): StreamingPhysics;
    timeToFirstToken(value: number, timeUnit?: string): Delay;
    embedding(): EmbeddingResponse;

    header(name: string): IsolationSource;
    queryParameter(name: string): IsolationSource;
    cookie(name: string): IsolationSource;

    defaultErrorBody(statusCode: number): string;
}

/**
 * The runtime value of `require('./llm')` — the LLM builder factory namespace.
 * Also exposes the builder/model constructors for advanced use and instanceof.
 */
declare const llm: Llm & {
    Completion: new () => Completion;
    ToolUse: new (name: string) => ToolUse;
    Usage: new () => Usage;
    StreamingPhysics: new () => StreamingPhysics;
    EmbeddingResponse: new () => EmbeddingResponse;
    LlmMockBuilder: new (path: string) => LlmMockBuilder;
    LlmConversationBuilder: new () => LlmConversationBuilder;
    LlmFailoverBuilder: new () => LlmFailoverBuilder;
    IsolationSource: new (kind: string, name: string) => IsolationSource;
};

export default llm;
