/**
 * Codegen for MockServer Load Scenarios — emits snippets that REGISTER the
 * current authored scenario and then START it, for curl, the raw JSON wire
 * bodies, and the client languages the Composer/Verification panels already
 * support (Java, Node.js, Python, Go, C#, Ruby, Rust).
 *
 * The registry control plane is JSON-over-REST rather than a fluent builder, so
 * every language renders the same two requests:
 *   1. PUT  /mockserver/loadScenario        (body = the LoadScenario JSON) — register
 *   2. PUT  /mockserver/loadScenario/start   (body = {"name": "<name>"})   — run
 *
 * Snippet style (raw-JSON-string deserialisation, host/port parsing) reuses the
 * helpers from standardCodegen.ts so the output is consistent with the other
 * panels' code generation.
 */

import {
  clientHostPort,
  toPythonLiteral,
  rustRawString,
  indentAfterFirst,
} from './standardCodegen';
import type { LoadScenarioDTO } from './loadScenario';

export interface LoadScenarioCodegenInput {
  /** The scenario to register (already validated/built from the author form). */
  scenario: LoadScenarioDTO;
  /** MockServer base URL for the REST calls. */
  baseUrl: string;
}

const REGISTER_PATH = '/mockserver/loadScenario';
const START_PATH = '/mockserver/loadScenario/start';

/** The {"name": "<name>"} body the /start endpoint takes for a single scenario. */
function startBody(name: string): Record<string, string> {
  return { name };
}

// ---------------------------------------------------------------------------
// JSON output — the two raw PUT bodies, clearly labelled.
// ---------------------------------------------------------------------------

export function loadToJson(input: LoadScenarioCodegenInput): string {
  const register = JSON.stringify(input.scenario, null, 2);
  const start = JSON.stringify(startBody(input.scenario.name), null, 2);
  return [
    `// 1. Register — PUT ${REGISTER_PATH}`,
    register,
    '',
    `// 2. Start — PUT ${START_PATH}`,
    start,
  ].join('\n');
}

// ---------------------------------------------------------------------------
// curl output — two PUT commands.
// ---------------------------------------------------------------------------

function shellSafe(json: string): string {
  return json.replace(/'/g, `'\\''`);
}

export function loadToCurl(input: LoadScenarioCodegenInput): string {
  const register = shellSafe(JSON.stringify(input.scenario));
  const start = shellSafe(JSON.stringify(startBody(input.scenario.name)));
  return [
    `# 1. Register the scenario`,
    `curl -v -X PUT '${input.baseUrl}${REGISTER_PATH}' \\`,
    `  -H 'Content-Type: application/json' \\`,
    `  -d '${register}'`,
    '',
    `# 2. Start it (requires loadGenerationEnabled=true)`,
    `curl -v -X PUT '${input.baseUrl}${START_PATH}' \\`,
    `  -H 'Content-Type: application/json' \\`,
    `  -d '${start}'`,
  ].join('\n');
}

// ---------------------------------------------------------------------------
// Java — JSON deserialisation + the two PUTs over the client's generic sender.
// The Java client has no first-class load-scenario API, so use a small HttpClient.
// ---------------------------------------------------------------------------

export function loadToJava(input: LoadScenarioCodegenInput): string {
  const { host, port } = clientHostPort(input.baseUrl);
  const registerJson = JSON.stringify(input.scenario);
  const startJson = JSON.stringify(startBody(input.scenario.name));
  const javaString = (s: string) => s.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  return [
    'import java.net.URI;',
    'import java.net.http.HttpClient;',
    'import java.net.http.HttpRequest;',
    'import java.net.http.HttpResponse;',
    '',
    `String baseUrl = "http://${host}:${port}";`,
    'HttpClient http = HttpClient.newHttpClient();',
    '',
    '// 1. Register the scenario',
    `String registerBody = "${javaString(registerJson)}";`,
    'http.send(HttpRequest.newBuilder()',
    `    .uri(URI.create(baseUrl + "${REGISTER_PATH}"))`,
    '    .header("Content-Type", "application/json")',
    '    .PUT(HttpRequest.BodyPublishers.ofString(registerBody))',
    '    .build(), HttpResponse.BodyHandlers.ofString());',
    '',
    '// 2. Start it (requires loadGenerationEnabled=true)',
    `String startBody = "${javaString(startJson)}";`,
    'http.send(HttpRequest.newBuilder()',
    `    .uri(URI.create(baseUrl + "${START_PATH}"))`,
    '    .header("Content-Type", "application/json")',
    '    .PUT(HttpRequest.BodyPublishers.ofString(startBody))',
    '    .build(), HttpResponse.BodyHandlers.ofString());',
  ].join('\n');
}

// ---------------------------------------------------------------------------
// Node.js — fetch the two REST endpoints.
// ---------------------------------------------------------------------------

export function loadToNode(input: LoadScenarioCodegenInput): string {
  const { host, port } = clientHostPort(input.baseUrl);
  const registerJson = JSON.stringify(input.scenario, null, 2);
  const startJson = JSON.stringify(startBody(input.scenario.name), null, 2);
  return [
    `const baseUrl = "http://${host}:${port}";`,
    '',
    'async function runLoadScenario() {',
    '  // 1. Register the scenario',
    `  await fetch(baseUrl + "${REGISTER_PATH}", {`,
    '    method: "PUT",',
    '    headers: { "Content-Type": "application/json" },',
    `    body: JSON.stringify(${indentAfterFirst(registerJson, 4)}),`,
    '  });',
    '',
    '  // 2. Start it (requires loadGenerationEnabled=true)',
    `  await fetch(baseUrl + "${START_PATH}", {`,
    '    method: "PUT",',
    '    headers: { "Content-Type": "application/json" },',
    `    body: JSON.stringify(${indentAfterFirst(startJson, 4)}),`,
    '  });',
    '}',
    '',
    'runLoadScenario().catch(console.error);',
  ].join('\n');
}

// ---------------------------------------------------------------------------
// Python — requests against the two endpoints.
// ---------------------------------------------------------------------------

export function loadToPython(input: LoadScenarioCodegenInput): string {
  const { host, port } = clientHostPort(input.baseUrl);
  const registerDict = toPythonLiteral(input.scenario as unknown, 0);
  const startDict = toPythonLiteral(startBody(input.scenario.name) as unknown, 0);
  return [
    'import requests',
    '',
    `base_url = "http://${host}:${port}"`,
    '',
    '# 1. Register the scenario',
    `register_body = ${registerDict}`,
    `requests.put(base_url + "${REGISTER_PATH}", json=register_body)`,
    '',
    '# 2. Start it (requires loadGenerationEnabled=true)',
    `start_body = ${startDict}`,
    `requests.put(base_url + "${START_PATH}", json=start_body)`,
  ].join('\n');
}

// ---------------------------------------------------------------------------
// Go — net/http with the raw JSON bodies.
// ---------------------------------------------------------------------------

export function loadToGo(input: LoadScenarioCodegenInput): string {
  const { host, port } = clientHostPort(input.baseUrl);
  const registerJson = JSON.stringify(input.scenario);
  const startJson = JSON.stringify(startBody(input.scenario.name));
  return [
    'package main',
    '',
    'import (',
    '\t"net/http"',
    '\t"strings"',
    ')',
    '',
    'func main() {',
    `\tbaseURL := "http://${host}:${port}"`,
    '',
    '\t// 1. Register the scenario',
    `\tregisterBody := ${goRawString(registerJson)}`,
    `\treq1, _ := http.NewRequest("PUT", baseURL+"${REGISTER_PATH}", strings.NewReader(registerBody))`,
    '\treq1.Header.Set("Content-Type", "application/json")',
    '\thttp.DefaultClient.Do(req1)',
    '',
    '\t// 2. Start it (requires loadGenerationEnabled=true)',
    `\tstartBody := ${goRawString(startJson)}`,
    `\treq2, _ := http.NewRequest("PUT", baseURL+"${START_PATH}", strings.NewReader(startBody))`,
    '\treq2.Header.Set("Content-Type", "application/json")',
    '\thttp.DefaultClient.Do(req2)',
    '}',
  ].join('\n');
}

/** Go raw string literal (backtick-delimited); falls back to a quoted string if a backtick is present. */
function goRawString(s: string): string {
  if (!s.includes('`')) return '`' + s + '`';
  return JSON.stringify(s);
}

// ---------------------------------------------------------------------------
// C# — HttpClient with verbatim string JSON bodies.
// ---------------------------------------------------------------------------

export function loadToCsharp(input: LoadScenarioCodegenInput): string {
  const { host, port } = clientHostPort(input.baseUrl);
  const registerJson = JSON.stringify(input.scenario).replace(/"/g, '""');
  const startJson = JSON.stringify(startBody(input.scenario.name)).replace(/"/g, '""');
  return [
    'using System.Net.Http;',
    'using System.Text;',
    '',
    `var baseUrl = "http://${host}:${port}";`,
    'using var http = new HttpClient();',
    '',
    '// 1. Register the scenario',
    `var registerBody = new StringContent(@"${registerJson}", Encoding.UTF8, "application/json");`,
    `await http.PutAsync(baseUrl + "${REGISTER_PATH}", registerBody);`,
    '',
    '// 2. Start it (requires loadGenerationEnabled=true)',
    `var startBody = new StringContent(@"${startJson}", Encoding.UTF8, "application/json");`,
    `await http.PutAsync(baseUrl + "${START_PATH}", startBody);`,
  ].join('\n');
}

// ---------------------------------------------------------------------------
// Ruby — net/http with heredoc JSON bodies.
// ---------------------------------------------------------------------------

export function loadToRuby(input: LoadScenarioCodegenInput): string {
  const { host, port } = clientHostPort(input.baseUrl);
  const registerJson = JSON.stringify(input.scenario, null, 2);
  const startJson = JSON.stringify(startBody(input.scenario.name), null, 2);
  const heredoc = (json: string) => ["<<~'JSON'", json.split('\n').map((l) => '  ' + l).join('\n'), 'JSON'].join('\n');
  return [
    "require 'net/http'",
    "require 'uri'",
    '',
    `base_url = 'http://${host}:${port}'`,
    '',
    '# 1. Register the scenario',
    `register_body = ${heredoc(registerJson)}`,
    `uri = URI("#{base_url}${REGISTER_PATH}")`,
    "req = Net::HTTP::Put.new(uri, 'Content-Type' => 'application/json')",
    'req.body = register_body',
    'Net::HTTP.start(uri.hostname, uri.port) { |h| h.request(req) }',
    '',
    '# 2. Start it (requires loadGenerationEnabled=true)',
    `start_body = ${heredoc(startJson)}`,
    `uri = URI("#{base_url}${START_PATH}")`,
    "req = Net::HTTP::Put.new(uri, 'Content-Type' => 'application/json')",
    'req.body = start_body',
    'Net::HTTP.start(uri.hostname, uri.port) { |h| h.request(req) }',
  ].join('\n');
}

// ---------------------------------------------------------------------------
// Rust — reqwest (blocking) with raw-string JSON bodies.
// ---------------------------------------------------------------------------

export function loadToRust(input: LoadScenarioCodegenInput): string {
  const { host, port } = clientHostPort(input.baseUrl);
  const registerJson = JSON.stringify(input.scenario, null, 2);
  const startJson = JSON.stringify(startBody(input.scenario.name), null, 2);
  return [
    '// Cargo.toml: reqwest = { version = "0.12", features = ["blocking", "json"] }',
    'use reqwest::blocking::Client;',
    '',
    'fn main() -> Result<(), Box<dyn std::error::Error>> {',
    `    let base_url = "http://${host}:${port}";`,
    '    let client = Client::new();',
    '',
    '    // 1. Register the scenario',
    `    let register_body = ${rustRawString(registerJson)};`,
    `    client.put(format!("{}${REGISTER_PATH}", base_url))`,
    '        .header("Content-Type", "application/json")',
    '        .body(register_body)',
    '        .send()?;',
    '',
    '    // 2. Start it (requires loadGenerationEnabled=true)',
    `    let start_body = ${rustRawString(startJson)};`,
    `    client.put(format!("{}${START_PATH}", base_url))`,
    '        .header("Content-Type", "application/json")',
    '        .body(start_body)',
    '        .send()?;',
    '',
    '    Ok(())',
    '}',
  ].join('\n');
}
