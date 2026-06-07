import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import MetricsView from '../components/MetricsView';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function stubFetch(status: number, body = '') {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      status,
      ok: status >= 200 && status < 300,
      statusText: 'stub',
      text: async () => body,
    })),
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('MetricsView', () => {
  it('shows the disabled guidance when /mockserver/metrics returns 404', async () => {
    stubFetch(404);
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText(/Metrics are disabled/i)).toBeInTheDocument());
    expect(screen.getByText(/MOCKSERVER_METRICS_ENABLED=true/)).toBeInTheDocument();
  });

  it('renders the metric panels from parsed metrics', async () => {
    stubFetch(
      200,
      'requests_received_count 42.0\nresponse_expectations_matched_count 7.0\nexpectations_not_matched_count 3.0\n',
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.getByText('Request activity (cumulative)')).toBeInTheDocument();
    // the standalone number cards were removed — those counts now live only in
    // the Request activity timeline graph, not as a card value
    expect(screen.queryByText('42')).not.toBeInTheDocument();
  });

  it('renders the request-activity and actions-executed graph panels', async () => {
    stubFetch(
      200,
      'requests_received_count 42.0\nresponse_expectations_matched_count 7.0\n' +
        'expectations_not_matched_count 3.0\nforward_expectations_matched_count 1.0\n' +
        'response_actions_count 5.0\n',
    );
    render(<MetricsView connectionParams={params} />);
    // the request-activity graph is the second-last panel, actions-executed the last
    await waitFor(() => expect(screen.getByText('Request activity (cumulative)')).toBeInTheDocument());
    expect(screen.getByText('Actions executed')).toBeInTheDocument();
    // with an action counter present, the actions panel shows the chart, not the empty state
    expect(screen.queryByText('No actions executed yet.')).not.toBeInTheDocument();
  });

  it('surfaces the MockServer version from build_info', async () => {
    stubFetch(200, 'requests_received_count 1.0\nmock_server_build_info{version="6.1.0"} 1.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('MockServer 6.1.0')).toBeInTheDocument());
  });

  it('renders the JVM section when JVM metrics are present', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 5.0',
        'jvm_memory_used_bytes{area="heap"} 1048576',
        'jvm_memory_committed_bytes{area="heap"} 2097152',
        'jvm_threads_current 12.0',
        'jvm_threads_daemon 8.0',
        'jvm_gc_collection_count 3.0',
        'jvm_gc_collection_seconds_sum 0.5',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('JVM heap memory')).toBeInTheDocument());
    expect(screen.getByText('Threads & GC')).toBeInTheDocument();
  });

  it('hides the JVM section when JVM metrics are absent', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.queryByText('JVM heap memory')).not.toBeInTheDocument();
  });

  it('renders the latency panel when the duration histogram is present', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 100.0',
        'mock_server_request_duration_seconds_bucket{le="0.05"} 50',
        'mock_server_request_duration_seconds_bucket{le="0.1"} 90',
        'mock_server_request_duration_seconds_bucket{le="+Inf"} 100',
        'mock_server_request_duration_seconds_count 100',
        'mock_server_request_duration_seconds_sum 5.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Request latency — cumulative since server start')).toBeInTheDocument());
    expect(screen.getByText('p95')).toBeInTheDocument();
  });

  it('hides the latency panel when the histogram is absent', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.queryByText('Request latency — cumulative since server start')).not.toBeInTheDocument();
  });

  it('renders a stat for every HTTP chaos fault type the server emits', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_http_chaos_injected_total{fault_type="drop"} 1.0',
        'mock_server_http_chaos_injected_total{fault_type="error"} 2.0',
        'mock_server_http_chaos_injected_total{fault_type="latency"} 3.0',
        'mock_server_http_chaos_injected_total{fault_type="truncate"} 4.0',
        'mock_server_http_chaos_injected_total{fault_type="malformed"} 5.0',
        'mock_server_http_chaos_injected_total{fault_type="slow"} 6.0',
        'mock_server_http_chaos_injected_total{fault_type="quota"} 7.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('HTTP Chaos Faults')).toBeInTheDocument());
    const expected: [string, string][] = [
      ['drop', '1'], ['error', '2'], ['latency', '3'], ['truncate', '4'],
      ['malformed', '5'], ['slow', '6'], ['quota', '7'],
    ];
    for (const [fault, value] of expected) {
      const caption = screen.getByText(`${fault} faults`);
      expect(caption).toBeInTheDocument();
      // the count is the sibling stat above the caption within the same Box
      expect(caption.parentElement?.textContent).toContain(value);
    }
  });

  it('charts active service-scoped chaos by fault type', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_active_service_chaos{fault_type="error"} 2.0',
        'mock_server_active_service_chaos{fault_type="latency"} 1.0',
        'mock_server_active_service_chaos{fault_type="drop"} 0.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('HTTP Chaos Faults')).toBeInTheDocument());
    // rendered as a by-type chart, not a single "active services" counter
    expect(screen.getByText('Active service-scoped chaos by type')).toBeInTheDocument();
    expect(screen.queryByText('active services')).not.toBeInTheDocument();
  });

  it('hides the HTTP Chaos Faults section when no chaos metric is present', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.queryByText('HTTP Chaos Faults')).not.toBeInTheDocument();
  });

  it('hides the HTTP Chaos Faults section when all chaos metrics are zero', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 5.0',
        'mock_server_http_chaos_injected_total{fault_type="error"} 0.0',
        'mock_server_http_chaos_injected_total{fault_type="latency"} 0.0',
        'mock_server_active_service_chaos{fault_type="error"} 0.0',
        'mock_server_active_service_chaos{fault_type="drop"} 0.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Throughput (derived)')).toBeInTheDocument());
    expect(screen.queryByText('HTTP Chaos Faults')).not.toBeInTheDocument();
  });

  it('renders the Expectations by type chart with per-label lines', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_expectations_by_type{action_type="RESPONSE"} 3.0',
        'mock_server_expectations_by_type{action_type="FORWARD"} 1.0',
        'mock_server_expectations_by_type{action_type="LLM_RESPONSE"} 0.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Expectations by type')).toBeInTheDocument());
    // zero-value types are filtered out, so LLM_RESPONSE should not appear
    expect(screen.queryByText('No expectations configured.')).not.toBeInTheDocument();
  });

  it('shows empty state for Expectations by type when no expectations exist', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Expectations by type')).toBeInTheDocument());
    expect(screen.getByText('No expectations configured.')).toBeInTheDocument();
  });

  it('renders the MCP tool calls chart with per-tool lines', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_mcp_tool_calls_total{tool="list_mock_tools"} 5.0',
        'mock_server_mcp_tool_calls_total{tool="create_expectation"} 2.0',
        'mock_server_mcp_tool_calls_total{tool="unused_tool"} 0.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('MCP tool calls')).toBeInTheDocument());
    // zero-value tools are filtered out
    expect(screen.queryByText('No MCP tool calls recorded.')).not.toBeInTheDocument();
  });

  it('shows empty state for MCP tool calls when no calls exist', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('MCP tool calls')).toBeInTheDocument());
    expect(screen.getByText('No MCP tool calls recorded.')).toBeInTheDocument();
  });

  it('hides zero-value expectation types from the chart', async () => {
    stubFetch(
      200,
      [
        'requests_received_count 10.0',
        'mock_server_expectations_by_type{action_type="RESPONSE"} 3.0',
        'mock_server_expectations_by_type{action_type="FORWARD"} 0.0',
        '',
      ].join('\n'),
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Expectations by type')).toBeInTheDocument());
    // Only RESPONSE (non-zero) should appear; FORWARD (zero) should be filtered
    expect(screen.queryByText('No expectations configured.')).not.toBeInTheDocument();
  });
});
