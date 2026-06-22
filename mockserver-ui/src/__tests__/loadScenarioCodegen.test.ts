/**
 * Tests for the load-scenario code generators — Java, Node.js, Python, Go, C#,
 * Ruby, Rust, JSON, and curl. Every snippet must perform the two REST calls:
 * register (PUT /loadScenario) then start (PUT /loadScenario/start with the
 * scenario name).
 *
 * Assertions check for the correct endpoints, the scenario name, and the start
 * body shape without over-asserting exact whitespace.
 */
import { describe, it, expect } from 'vitest';
import {
  loadToJava,
  loadToJson,
  loadToCurl,
  loadToNode,
  loadToPython,
  loadToGo,
  loadToCsharp,
  loadToRuby,
  loadToRust,
  type LoadScenarioCodegenInput,
} from '../lib/loadScenarioCodegen';
import type { LoadScenarioDTO } from '../lib/loadScenario';

const BASE_URL = 'http://localhost:1080';

const SCENARIO: LoadScenarioDTO = {
  name: 'checkout-load',
  templateType: 'VELOCITY',
  startDelayMillis: 5000,
  profile: { stages: [{ type: 'VU', vus: 5, durationMillis: 30000 }] },
  steps: [
    { request: { method: 'GET', path: '/api/item', socketAddress: { host: 'target.svc', port: 8080, scheme: 'HTTP' } } },
  ],
};

const INPUT: LoadScenarioCodegenInput = { scenario: SCENARIO, baseUrl: BASE_URL };

describe('loadScenarioCodegen', () => {
  describe('loadToJson', () => {
    it('emits both the register body and the start body', () => {
      const out = loadToJson(INPUT);
      expect(out).toContain('/mockserver/loadScenario');
      expect(out).toContain('/mockserver/loadScenario/start');
      expect(out).toContain('"name": "checkout-load"');
      expect(out).toContain('"startDelayMillis": 5000');
      // The start body is the {"name": ...} shape.
      const startBodyIndex = out.indexOf('/start');
      expect(out.slice(startBodyIndex)).toContain('"name": "checkout-load"');
    });
  });

  describe('loadToCurl', () => {
    it('emits two PUT commands hitting register then start', () => {
      const out = loadToCurl(INPUT);
      expect(out).toContain(`curl -v -X PUT '${BASE_URL}/mockserver/loadScenario'`);
      expect(out).toContain(`curl -v -X PUT '${BASE_URL}/mockserver/loadScenario/start'`);
      expect(out).toContain(`{"name":"checkout-load"}`);
    });

    it('shell-escapes single quotes in the JSON body', () => {
      const tricky: LoadScenarioCodegenInput = {
        baseUrl: BASE_URL,
        scenario: { ...SCENARIO, steps: [{ request: { path: "/it's", socketAddress: { host: 'h', port: 80 } } }] },
      };
      const out = loadToCurl(tricky);
      expect(out).toContain(`'\\''`);
    });
  });

  const langs: Array<[string, (i: LoadScenarioCodegenInput) => string]> = [
    ['Java', loadToJava],
    ['Node', loadToNode],
    ['Python', loadToPython],
    ['Go', loadToGo],
    ['C#', loadToCsharp],
    ['Ruby', loadToRuby],
    ['Rust', loadToRust],
  ];

  it.each(langs)('%s snippet hits both endpoints and references the scenario name', (_label, gen) => {
    const out = gen(INPUT);
    expect(out).toContain('/mockserver/loadScenario');
    expect(out).toContain('/mockserver/loadScenario/start');
    expect(out).toContain('checkout-load');
    // host/port from the base URL are threaded through.
    expect(out).toContain('localhost');
  });

  it('Node uses fetch with PUT', () => {
    const out = loadToNode(INPUT);
    expect(out).toContain('method: "PUT"');
    expect(out).toMatch(/fetch\(baseUrl \+ "\/mockserver\/loadScenario"/);
  });

  it('Python uses requests.put against both endpoints', () => {
    const out = loadToPython(INPUT);
    expect(out).toContain('requests.put(base_url + "/mockserver/loadScenario"');
    expect(out).toContain('requests.put(base_url + "/mockserver/loadScenario/start"');
    expect(out).toContain('start_body = {');
  });

  it('Rust embeds the JSON as a raw string and uses reqwest put', () => {
    const out = loadToRust(INPUT);
    expect(out).toContain('client.put(');
    expect(out).toMatch(/r#?"/); // raw string literal
  });

  it('C# escapes embedded quotes for the verbatim string', () => {
    const out = loadToCsharp(INPUT);
    expect(out).toContain('@"');
    expect(out).toContain('""name""'); // doubled quotes in verbatim string
  });
});
