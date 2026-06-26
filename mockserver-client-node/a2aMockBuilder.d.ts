/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

import {Expectation} from './mockServer';
import {MockServerClient, RequestResponse} from './mockServerClient';

/**
 * Fluent builder for a single advertised A2A skill. Obtained via
 * A2aMockBuilder.withSkill(id); call .and() to return to the parent builder.
 */
export interface A2aSkillBuilder {
    /** Human readable skill name (defaults to the skill id when omitted). */
    withName(name: string): A2aSkillBuilder;

    /** Human readable description of the skill. */
    withDescription(description: string): A2aSkillBuilder;

    /** Add a tag advertised on the agent card for this skill. */
    withTag(tag: string): A2aSkillBuilder;

    /** Add an example utterance advertised on the agent card for this skill. */
    withExample(example: string): A2aSkillBuilder;

    /** Finish this skill and return to the parent A2A mock builder. */
    and(): A2aMockBuilder;
}

/**
 * Fluent builder for a custom tasks/send handler matched by a message regex.
 * Obtained via A2aMockBuilder.onTaskSend(); call .and() to return to the parent
 * builder.
 */
export interface A2aTaskHandlerBuilder {
    /** Regex matched against params.message.parts[0].text (default ".*"). */
    matchingMessage(pattern: string): A2aTaskHandlerBuilder;

    /**
     * The text returned in the task artifact for a matching tasks/send.
     * @param isError when true the task state is "failed" rather than "completed"
     */
    respondingWith(text: string, isError?: boolean): A2aTaskHandlerBuilder;

    /** Finish this handler and return to the parent A2A mock builder. */
    and(): A2aMockBuilder;
}

/**
 * Fluent builder that produces the set of MockServer expectations needed to
 * mock an A2A (Agent-to-Agent) agent. Mirrors the Java client A2aMockBuilder:
 * a static agent-card GET, JSON-RPC 2.0 tasks/send, tasks/get and tasks/cancel
 * over POST, plus optional SSE streaming and push-notification config/delivery.
 */
export interface A2aMockBuilder {
    withAgentName(name: string): A2aMockBuilder;

    withAgentDescription(description: string): A2aMockBuilder;

    withAgentVersion(version: string): A2aMockBuilder;

    withAgentUrl(url: string): A2aMockBuilder;

    /** Override the path the agent card is served from (default "/.well-known/agent.json"). */
    withAgentCardPath(path: string): A2aMockBuilder;

    /** The default artifact text returned by tasks/send and tasks/get. */
    withDefaultTaskResponse(response: string): A2aMockBuilder;

    /** Advertise and mock the streaming capability (SSE) with the default method. */
    withStreaming(): A2aMockBuilder;

    /** Advertise and mock streaming with a custom JSON-RPC method (implies withStreaming()). */
    withStreamingMethod(method: string): A2aMockBuilder;

    /**
     * Advertise and mock push notifications: echo the registered config and POST
     * each completed task to the supplied webhook URL while still returning the
     * JSON-RPC task response to the caller.
     */
    withPushNotifications(webhookUrl: string): A2aMockBuilder;

    /** Start declaring an advertised skill (id, name, description, tags, examples). */
    withSkill(id: string): A2aSkillBuilder;

    /** Start declaring a custom tasks/send handler matched by a message regex. */
    onTaskSend(): A2aTaskHandlerBuilder;

    /** Build the generated expectations without registering them. */
    build(): Expectation[];

    /**
     * Build and register the generated expectations on the supplied client.
     * When obtained from client.a2aMock(...) the client argument may be omitted
     * to use that client.
     */
    applyTo(client?: MockServerClient): Promise<RequestResponse>;
}

/**
 * Start building a mock A2A agent.
 *
 * @param path the HTTP path the A2A agent is mounted on (default "/a2a")
 */
export declare function a2aMock(path?: string): A2aMockBuilder;
