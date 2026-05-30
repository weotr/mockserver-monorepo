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

  it('renders summary panels and values from parsed metrics', async () => {
    stubFetch(
      200,
      'requests_received_count 42.0\nresponse_expectations_matched_count 7.0\nexpectations_not_matched_count 3.0\n',
    );
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Requests received')).toBeInTheDocument());
    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.getByText('Matched')).toBeInTheDocument();
    expect(screen.getByText('7')).toBeInTheDocument();
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
    await waitFor(() => expect(screen.getByText('Requests received')).toBeInTheDocument());
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
    await waitFor(() => expect(screen.getByText('Request latency (since start)')).toBeInTheDocument());
    expect(screen.getByText('p95')).toBeInTheDocument();
  });

  it('hides the latency panel when the histogram is absent', async () => {
    stubFetch(200, 'requests_received_count 5.0\n');
    render(<MetricsView connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('Requests received')).toBeInTheDocument());
    expect(screen.queryByText('Request latency (since start)')).not.toBeInTheDocument();
  });
});
