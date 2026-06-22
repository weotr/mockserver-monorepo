import { describe, it, expect } from 'vitest';
import mermaidLib from 'mermaid';
import { parseCallGraph, buildRenderModel, toMermaid, type CallGraph } from '../lib/callGraph';

// graph shaped like explain_agent_run's callGraph for a tool-using turn
const RAW = {
  nodes: [
    { id: 'm0', kind: 'USER', label: 'weather in paris?' },
    { id: 'm1', kind: 'ASSISTANT', label: 'ASSISTANT' },
    { id: 'm1_tc0', kind: 'TOOL_CALL', label: 'get_weather' },
    { id: 'm2', kind: 'TOOL', label: 'TOOL' },
  ],
  edges: [
    { from: 'm0', to: 'm1', kind: 'NEXT' },
    { from: 'm1', to: 'm2', kind: 'NEXT' },
    { from: 'm1', to: 'm1_tc0', kind: 'INVOKES' },
    { from: 'm1_tc0', to: 'm2', kind: 'RESULT' },
  ],
};

describe('parseCallGraph', () => {
  it('parses a well-formed graph', () => {
    const g = parseCallGraph(RAW);
    expect(g).not.toBeNull();
    expect(g!.nodes.length).toBe(4);
    expect(g!.edges.length).toBe(4);
  });

  it('returns null for non-objects and tolerates missing arrays', () => {
    expect(parseCallGraph(null)).toBeNull();
    expect(parseCallGraph('x')).toBeNull();
    expect(parseCallGraph({})).toEqual({ nodes: [], edges: [] });
  });
});

describe('buildRenderModel', () => {
  it('produces message steps with invoked tool calls and result flags', () => {
    const steps = buildRenderModel(parseCallGraph(RAW) as CallGraph);
    // 3 message nodes (USER, ASSISTANT, TOOL) — tool-call node is nested, not a step
    expect(steps.length).toBe(3);
    const assistant = steps.find((s) => s.node.kind === 'ASSISTANT')!;
    expect(assistant.toolCalls.length).toBe(1);
    expect(assistant.toolCalls[0]!.node.label).toBe('get_weather');
    expect(assistant.toolCalls[0]!.hasResult).toBe(true);
    // user step has no tool calls
    expect(steps.find((s) => s.node.kind === 'USER')!.toolCalls.length).toBe(0);
  });

  it('marks tool calls without a result edge as hasResult=false', () => {
    const noResult: CallGraph = {
      nodes: [
        { id: 'm0', kind: 'ASSISTANT', label: 'a' },
        { id: 'm0_tc0', kind: 'TOOL_CALL', label: 'search' },
      ],
      edges: [{ from: 'm0', to: 'm0_tc0', kind: 'INVOKES' }],
    };
    const steps = buildRenderModel(noResult);
    expect(steps[0]!.toolCalls[0]!.hasResult).toBe(false);
  });
});

describe('toMermaid', () => {
  it('emits a flowchart with nodes and labelled edges', () => {
    const mermaid = toMermaid(parseCallGraph(RAW) as CallGraph);
    expect(mermaid.startsWith('flowchart TD')).toBe(true);
    // tool-call labels are QUOTED inside the stadium shape so special characters
    // in the label can't break Mermaid's parser.
    expect(mermaid).toContain('m1_tc0(["get_weather"])');
    expect(mermaid).toContain('m1_tc0 -->|RESULT| m2');
    // no HTML tags (GitHub mermaid renderer rejects them)
    expect(mermaid.includes('<br')).toBe(false);
  });

  it('produces source Mermaid can parse even with special characters in labels', async () => {
    // Regression: an unquoted tool-call stadium `([search(location)])` made
    // Mermaid throw a parse error, so the Sessions graph fell back to source.
    mermaidLib.initialize({ startOnLoad: false, securityLevel: 'strict' });
    const adversarial: CallGraph = {
      nodes: [
        { id: 'm0', kind: 'USER', label: 'What is the weather in Paris (today)?' },
        { id: 'm1', kind: 'ASSISTANT', label: 'It is 18°C — sunny [confirmed]' },
        { id: 'm1_tc0', kind: 'TOOL_CALL', label: 'search(location, units="metric")' },
        { id: 'm2', kind: 'TOOL', label: 'result: {"temp": 18}' },
      ],
      edges: [
        { from: 'm0', to: 'm1', kind: 'NEXT' },
        { from: 'm1', to: 'm1_tc0', kind: 'INVOKES' },
        { from: 'm1_tc0', to: 'm2', kind: 'RESULT' },
      ],
    };
    await expect(mermaidLib.parse(toMermaid(adversarial))).resolves.toBeTruthy();
  });
});
