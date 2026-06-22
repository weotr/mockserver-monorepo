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
 * Fluent builder for a single mock MCP tool. Obtained via
 * McpMockBuilder.withTool(name); call .and() to return to the parent builder.
 */
export interface McpToolBuilder {
    /** Human readable description of the tool. */
    withDescription(description: string): McpToolBuilder;

    /** JSON Schema (as a JSON string) describing the tool's input. */
    withInputSchema(jsonSchema: string): McpToolBuilder;

    /**
     * The text content returned by tools/call for this tool.
     * @param isError when true the response is flagged as an MCP error (default false)
     */
    respondingWith(textContent: string, isError?: boolean): McpToolBuilder;

    /** Finish this tool and return to the parent MCP mock builder. */
    and(): McpMockBuilder;
}

/**
 * Fluent builder for a single mock MCP resource. Obtained via
 * McpMockBuilder.withResource(uri); call .and() to return to the parent builder.
 */
export interface McpResourceBuilder {
    withName(name: string): McpResourceBuilder;

    withDescription(description: string): McpResourceBuilder;

    /** MIME type of the resource content (default "application/json"). */
    withMimeType(mimeType: string): McpResourceBuilder;

    /** The text content returned by resources/read for this resource. */
    withContent(content: string): McpResourceBuilder;

    /** Finish this resource and return to the parent MCP mock builder. */
    and(): McpMockBuilder;
}

/**
 * Fluent builder for a single mock MCP prompt. Obtained via
 * McpMockBuilder.withPrompt(name); call .and() to return to the parent builder.
 */
export interface McpPromptBuilder {
    withDescription(description: string): McpPromptBuilder;

    /** Declare a prompt argument advertised by prompts/list. */
    withArgument(name: string, description: string, required: boolean): McpPromptBuilder;

    /** Add a message returned by prompts/get (role is e.g. "user" or "assistant"). */
    respondingWith(role: string, textContent: string): McpPromptBuilder;

    /** Finish this prompt and return to the parent MCP mock builder. */
    and(): McpMockBuilder;
}

/**
 * Fluent builder that produces the set of MockServer expectations needed to
 * mock an MCP (Model Context Protocol) server speaking JSON-RPC 2.0 over the
 * Streamable HTTP transport. Mirrors the Java client McpMockBuilder.
 */
export interface McpMockBuilder {
    withServerName(name: string): McpMockBuilder;

    withServerVersion(version: string): McpMockBuilder;

    withProtocolVersion(version: string): McpMockBuilder;

    withToolsCapability(): McpMockBuilder;

    withResourcesCapability(): McpMockBuilder;

    withPromptsCapability(): McpMockBuilder;

    withTool(name: string): McpToolBuilder;

    withResource(uri: string): McpResourceBuilder;

    withPrompt(name: string): McpPromptBuilder;

    /** Build the generated expectations without registering them. */
    build(): Expectation[];

    /**
     * Build and register the generated expectations on the supplied client.
     * When obtained from client.mcpMock(...) the client argument may be
     * omitted to use that client.
     */
    applyTo(client?: MockServerClient): Promise<RequestResponse>;
}

/**
 * Start building a mock MCP server.
 *
 * @param path the HTTP path the MCP server is mounted on (default "/mcp")
 */
export declare function mcpMock(path?: string): McpMockBuilder;
