/**
 * Pure helpers for the agent-run call graph returned by the `explain_agent_run`
 * MCP tool (its `callGraph` field). Transforms the backend node/edge graph into
 * a render model (an ordered sequence of message steps, each with the tool calls
 * it invoked and whether a result returned) and a Mermaid flowchart string.
 */

export interface CallGraphNode {
  id: string;
  kind: string; // USER / ASSISTANT / SYSTEM / TOOL / TOOL_CALL
  label: string;
}

export interface CallGraphEdge {
  from: string;
  to: string;
  kind: string; // NEXT / INVOKES / RESULT
}

export interface CallGraph {
  nodes: CallGraphNode[];
  edges: CallGraphEdge[];
}

export interface RenderToolCall {
  node: CallGraphNode;
  hasResult: boolean;
}

export interface RenderStep {
  node: CallGraphNode;
  toolCalls: RenderToolCall[];
}

/** Parse a `callGraph` object (e.g. from an explain_agent_run result) defensively. */
export function parseCallGraph(raw: unknown): CallGraph | null {
  if (raw == null || typeof raw !== 'object') return null;
  const r = raw as Record<string, unknown>;
  const rawNodes = Array.isArray(r['nodes']) ? (r['nodes'] as unknown[]) : [];
  const rawEdges = Array.isArray(r['edges']) ? (r['edges'] as unknown[]) : [];
  const nodes: CallGraphNode[] = rawNodes
    .map((n) => n as Record<string, unknown>)
    .filter((n) => typeof n['id'] === 'string')
    .map((n) => ({
      id: n['id'] as string,
      kind: typeof n['kind'] === 'string' ? (n['kind'] as string) : 'UNKNOWN',
      label: typeof n['label'] === 'string' ? (n['label'] as string) : '',
    }));
  const edges: CallGraphEdge[] = rawEdges
    .map((e) => e as Record<string, unknown>)
    .filter((e) => typeof e['from'] === 'string' && typeof e['to'] === 'string')
    .map((e) => ({
      from: e['from'] as string,
      to: e['to'] as string,
      kind: typeof e['kind'] === 'string' ? (e['kind'] as string) : 'NEXT',
    }));
  return { nodes, edges };
}

const TOOL_CALL = 'TOOL_CALL';

/**
 * Build an ordered list of message steps. Message nodes (any kind other than
 * TOOL_CALL) are taken in their array order — the backend emits them in
 * conversation sequence — and each step carries the tool calls it INVOKES and
 * whether each produced a RESULT.
 */
export function buildRenderModel(graph: CallGraph): RenderStep[] {
  const byId = new Map<string, CallGraphNode>();
  graph.nodes.forEach((n) => byId.set(n.id, n));

  const invokes = new Map<string, string[]>(); // messageId -> toolCallIds
  const toolCallHasResult = new Set<string>(); // toolCallIds that produced a RESULT
  for (const edge of graph.edges) {
    if (edge.kind === 'INVOKES') {
      const list = invokes.get(edge.from) ?? [];
      list.push(edge.to);
      invokes.set(edge.from, list);
    } else if (edge.kind === 'RESULT') {
      toolCallHasResult.add(edge.from);
    }
  }

  const messageNodes = graph.nodes.filter((n) => n.kind !== TOOL_CALL);
  return messageNodes.map((node) => ({
    node,
    toolCalls: (invokes.get(node.id) ?? [])
      .map((id) => byId.get(id))
      .filter((n): n is CallGraphNode => n != null)
      .map((n) => ({ node: n, hasResult: toolCallHasResult.has(n.id) })),
  }));
}

function escapeMermaid(label: string): string {
  // Mermaid quoted labels: avoid quotes/newlines (GitHub renderer rejects HTML).
  return label.replace(/"/g, "'").replace(/\s+/g, ' ').trim().substring(0, 60);
}

/** Render the graph as a Mermaid `flowchart TD` string. */
export function toMermaid(graph: CallGraph): string {
  const lines: string[] = ['flowchart TD'];
  for (const node of graph.nodes) {
    // Both shapes use a QUOTED label so special characters in the text (e.g. a
    // tool name like `search(location)`, or message text with brackets/parens)
    // can't break Mermaid's parser. Stadium `(["..."])` for tool calls, rounded
    // rect `["..."]` for messages.
    const shape = node.kind === TOOL_CALL ? ['(["', '"])'] : ['["', '"]'];
    lines.push(`  ${node.id}${shape[0]}${node.kind === TOOL_CALL ? escapeMermaid(node.label) : escapeMermaid(node.kind + ': ' + node.label)}${shape[1]}`);
  }
  for (const edge of graph.edges) {
    lines.push(`  ${edge.from} -->|${edge.kind}| ${edge.to}`);
  }
  return lines.join('\n');
}
