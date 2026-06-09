import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ServiceChaosPanel from '../components/ServiceChaosPanel';

const params = { host: '127.0.0.1', port: '1080', secure: false };

interface PutCall {
  body: Record<string, unknown>;
}

/** Expand the HTTP Service Chaos section (collapsed by default). */
async function expandHttp(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Expand HTTP chaos' }));
}

/**
 * Stateful fetch stub: GET returns the current registry snapshot (mutable via
 * the returned `state`), PUT records the call into `puts`.
 *
 * Also serves empty-but-valid shapes for the gRPC-health, TCP-chaos, and
 * gRPC-chaos endpoints so their on-mount fetches succeed without polluting
 * the HTTP service-chaos assertions.
 */
function stubServiceChaos(initial: {
  services: Record<string, unknown>;
  ttlRemainingMillis?: Record<string, number>;
}, grpcHealthData: Record<string, string> = {}, grpcChaosData: { services: Record<string, unknown> } = { services: {} }) {
  const state = { ...initial };
  const puts: PutCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') {
        puts.push({ body: JSON.parse(String(init.body)) as Record<string, unknown> });
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ status: 'ok' }) };
      }
      const u = String(url);
      if (u.includes('/grpc/health')) {
        return { ok: true, status: 200, statusText: 'ok', json: async () => grpcHealthData };
      }
      if (u.includes('/tcpChaos')) {
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ hosts: {} }) };
      }
      if (u.includes('/grpcChaos')) {
        return { ok: true, status: 200, statusText: 'ok', json: async () => grpcChaosData };
      }
      return { ok: true, status: 200, statusText: 'ok', json: async () => state };
    }),
  );
  return { state, puts };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('ServiceChaosPanel', () => {
  it('lists registered hosts with profile summary chips', async () => {
    stubServiceChaos({
      services: { 'upstream.svc': { errorStatus: 503, errorProbability: 1.0, latency: { timeUnit: 'MILLISECONDS', value: 200 } } },
    });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('upstream.svc')).toBeInTheDocument());
    expect(screen.getByText('error 503 @ 100%')).toBeInTheDocument();
    expect(screen.getByText('+200ms latency')).toBeInTheDocument();
    expect(screen.getByText('1 active')).toBeInTheDocument();
  });

  it('shows a TTL countdown chip for a TTL-bearing registration', async () => {
    stubServiceChaos({
      services: { 'a.svc': { errorStatus: 500 } },
      ttlRemainingMillis: { 'a.svc': 65_000 },
    });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText(/auto-revert in/)).toBeInTheDocument());
    expect(screen.getByText(/auto-revert in 1m/)).toBeInTheDocument();
  });

  it('shows an empty state when nothing is registered', async () => {
    stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());
    // Both HTTP and gRPC panels show "0 active" — assert at least one exists
    const activeChips = screen.getAllByText('0 active');
    expect(activeChips.length).toBeGreaterThanOrEqual(1);
  });

  it('registers a host from the form', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'pay.svc');
    await user.type(screen.getByLabelText('Error status'), '503');
    await user.type(screen.getByLabelText('Error prob (0–1)'), '0.5');
    await user.type(screen.getByLabelText('TTL ms'), '60000');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'pay.svc',
      chaos: { errorStatus: 503, errorProbability: 0.5 },
      ttlMillis: 60000,
    });
  });

  it('rejects a register with a host but no fault set', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'pay.svc');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText(/Set at least one fault/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('rejects error probability without an error status', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'pay.svc');
    await user.type(screen.getByLabelText('Error prob (0–1)'), '0.3');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText(/needs an error status/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('removes a single host', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: { 'a.svc': { errorStatus: 503 } } });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('a.svc')).toBeInTheDocument());

    await expandHttp(user);
    await user.click(screen.getByRole('button', { name: 'Remove chaos for a.svc' }));
    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({ host: 'a.svc', remove: true });
  });

  it('clears all registrations', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: { 'a.svc': { errorStatus: 503 } } });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('a.svc')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Clear HTTP/ }));
    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({ clear: true });
  });

  it('surfaces a load error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: false, status: 500, statusText: 'Server Error', json: async () => ({}) })),
    );
    render(<ServiceChaosPanel connectionParams={params} />);
    expect(await screen.findByText('Could not load service chaos')).toBeInTheDocument();
  });

  it('renders a remove button scoped to each host', async () => {
    const user = userEvent.setup();
    stubServiceChaos({ services: { 'a.svc': { errorStatus: 503 }, 'b.svc': { errorStatus: 500 } } });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('a.svc')).toBeInTheDocument());
    await expandHttp(user);
    const rowA = screen.getByText('a.svc').closest('div');
    expect(rowA).not.toBeNull();
    expect(within(rowA!).getByText('error 503')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove chaos for a.svc' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove chaos for b.svc' })).toBeInTheDocument();
  });

  // --- Merged gRPC Chaos panel tests ---

  it('renders a merged gRPC Chaos panel with combined active count', async () => {
    stubServiceChaos(
      { services: {} },
      { 'payments.v1.PaymentService': 'NOT_SERVING', 'catalog.v1.CatalogService': 'SERVING' },
      { services: { 'orders.v1.OrderService': { errorStatusCode: 'UNAVAILABLE' } } },
    );
    render(<ServiceChaosPanel connectionParams={params} />);
    // The merged panel should show "3 active" (2 health + 1 fault)
    await waitFor(() => expect(screen.getByText('3 active')).toBeInTheDocument());
    // The panel header should say "gRPC Chaos"
    expect(screen.getByText('gRPC Chaos')).toBeInTheDocument();
  });

  it('renders Health Status and Fault Injection sub-sections inside gRPC Chaos panel', async () => {
    stubServiceChaos({ services: {} });
    const user = userEvent.setup();
    render(<ServiceChaosPanel connectionParams={params} />);
    // Expand the gRPC Chaos panel
    await waitFor(() => expect(screen.getByText('gRPC Chaos')).toBeInTheDocument());
    await user.click(screen.getByText('gRPC Chaos'));
    // Sub-sections should be visible
    expect(await screen.findByText('Health Status')).toBeInTheDocument();
    expect(screen.getByText('Fault Injection')).toBeInTheDocument();
  });

  it('shows GraphQL error chip for HTTP chaos with graphqlErrors', async () => {
    stubServiceChaos({
      services: { 'graphql.svc': { errorStatus: 200, graphqlErrors: true, graphqlErrorCode: 'RATE_LIMITED' } },
    });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('graphql.svc')).toBeInTheDocument());
    expect(screen.getByText('GraphQL error (RATE_LIMITED)')).toBeInTheDocument();
  });

  it('registers HTTP chaos with GraphQL errors enabled', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'graphql.svc');
    await user.type(screen.getByLabelText('Error status'), '200');
    // Enable GraphQL errors
    await user.click(screen.getByLabelText('GraphQL errors'));
    await user.type(screen.getByLabelText('Error message'), 'Rate limit exceeded');
    await user.type(screen.getByLabelText('Error code'), 'RATE_LIMITED');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'graphql.svc',
      chaos: {
        errorStatus: 200,
        graphqlErrors: true,
        graphqlErrorMessage: 'Rate limit exceeded',
        graphqlErrorCode: 'RATE_LIMITED',
        graphqlNullifyData: true,
      },
    });
  });

  it('starts with all three sections collapsed (Expand icons visible)', async () => {
    stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    // All three expand buttons should be present (indicating collapsed state)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Expand HTTP chaos' })).toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Expand gRPC chaos' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Expand TCP chaos' })).toBeInTheDocument();
    // None of the "Collapse" variants should be present
    expect(screen.queryByRole('button', { name: 'Collapse HTTP chaos' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Collapse gRPC chaos' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Collapse TCP chaos' })).not.toBeInTheDocument();
    // The HTTP register form's Host field should not be accessible while collapsed
    expect(screen.queryByRole('textbox', { name: 'Host' })).not.toBeInTheDocument();
  });

  it('has consistently named Clear buttons (Clear HTTP / Clear gRPC / Clear TCP)', async () => {
    stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('HTTP Service Chaos')).toBeInTheDocument());
    // All three clear buttons should be in the DOM with consistent naming
    expect(screen.getByRole('button', { name: /Clear HTTP/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Clear gRPC/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Clear TCP/ })).toBeInTheDocument();
  });

  it('shows omit grpc-status chip for gRPC chaos with omitGrpcStatus', async () => {
    stubServiceChaos(
      { services: {} },
      {},
      { services: { 'streaming.v1.StreamService': { errorStatusCode: 'INTERNAL', omitGrpcStatus: true } } },
    );
    const user = userEvent.setup();
    render(<ServiceChaosPanel connectionParams={params} />);
    // Expand gRPC Chaos panel, then Fault Injection sub-section
    await waitFor(() => expect(screen.getByText('gRPC Chaos')).toBeInTheDocument());
    await user.click(screen.getByText('gRPC Chaos'));
    await waitFor(() => expect(screen.getByText('Fault Injection')).toBeInTheDocument());
    await user.click(screen.getByText('Fault Injection'));
    await waitFor(() => expect(screen.getByText('streaming.v1.StreamService')).toBeInTheDocument());
    expect(screen.getByText('omit grpc-status')).toBeInTheDocument();
  });

  it('shows abort-after-messages chip for gRPC chaos with abortAfterMessages', async () => {
    stubServiceChaos(
      { services: {} },
      {},
      { services: { 'bidi.v1.BidiStream': { errorStatusCode: 'UNAVAILABLE', abortAfterMessages: 5 } } },
    );
    const user = userEvent.setup();
    render(<ServiceChaosPanel connectionParams={params} />);
    // Expand gRPC Chaos panel, then Fault Injection sub-section
    await waitFor(() => expect(screen.getByText('gRPC Chaos')).toBeInTheDocument());
    await user.click(screen.getByText('gRPC Chaos'));
    await waitFor(() => expect(screen.getByText('Fault Injection')).toBeInTheDocument());
    await user.click(screen.getByText('Fault Injection'));
    await waitFor(() => expect(screen.getByText('bidi.v1.BidiStream')).toBeInTheDocument());
    expect(screen.getByText('abort after 5 msgs')).toBeInTheDocument();
    // "UNAVAILABLE" appears in both the status code dropdown default and the chip
    const unavailableElements = screen.getAllByText('UNAVAILABLE');
    expect(unavailableElements.length).toBeGreaterThanOrEqual(1);
  });

  // --- Full chaos fault-type controls tests ---

  it('registers HTTP chaos with body corruption controls (truncate + malformed)', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'body.svc');
    await user.type(screen.getByLabelText('Truncate body (0–1)'), '0.5');
    await user.click(screen.getByLabelText('Malformed body'));
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'body.svc',
      chaos: { truncateBodyAtFraction: 0.5, malformedBody: true },
    });
  });

  it('registers HTTP chaos with slow response (chunk size + delay)', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'slow.svc');
    await user.type(screen.getByLabelText('Slow chunk bytes'), '64');
    await user.type(screen.getByLabelText('Slow chunk delay ms'), '500');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'slow.svc',
      chaos: {
        slowResponseChunkSize: 64,
        slowResponseChunkDelay: { timeUnit: 'MILLISECONDS', value: 500 },
      },
    });
  });

  it('registers HTTP chaos with quota (rate limit) controls', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'quota.svc');
    await user.type(screen.getByLabelText('Quota name'), 'api-quota');
    await user.type(screen.getByLabelText('Quota limit'), '100');
    await user.type(screen.getByLabelText('Quota window ms'), '60000');
    await user.type(screen.getByLabelText('Quota error status'), '429');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'quota.svc',
      chaos: {
        quotaName: 'api-quota',
        quotaLimit: 100,
        quotaWindowMillis: 60000,
        quotaErrorStatus: 429,
      },
    });
  });

  it('registers HTTP chaos with outage window and degradation ramp', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'outage.svc');
    await user.type(screen.getByLabelText('Error status'), '503');
    await user.type(screen.getByLabelText('Outage after ms'), '5000');
    await user.type(screen.getByLabelText('Outage duration ms'), '30000');
    await user.type(screen.getByLabelText('Degradation ramp ms'), '60000');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'outage.svc',
      chaos: {
        errorStatus: 503,
        outageAfterMillis: 5000,
        outageDurationMillis: 30000,
        degradationRampMillis: 60000,
      },
    });
  });

  it('registers HTTP chaos with retry-after header', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'retry.svc');
    await user.type(screen.getByLabelText('Error status'), '429');
    await user.type(screen.getByLabelText('Retry-After'), '120');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'retry.svc',
      chaos: { errorStatus: 429, retryAfter: '120' },
    });
  });

  it('registers a full chaos profile with all fault types', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'full.svc');
    await user.type(screen.getByLabelText('Error status'), '503');
    await user.type(screen.getByLabelText('Error prob (0–1)'), '0.8');
    await user.type(screen.getByLabelText('Retry-After'), '60');
    await user.type(screen.getByLabelText('Drop prob (0–1)'), '0.1');
    await user.type(screen.getByLabelText('Latency ms'), '200');
    await user.type(screen.getByLabelText('Truncate body (0–1)'), '0.75');
    await user.click(screen.getByLabelText('Malformed body'));
    await user.type(screen.getByLabelText('Slow chunk bytes'), '32');
    await user.type(screen.getByLabelText('Slow chunk delay ms'), '250');
    await user.type(screen.getByLabelText('Quota name'), 'test-quota');
    await user.type(screen.getByLabelText('Quota limit'), '50');
    await user.type(screen.getByLabelText('Quota window ms'), '30000');
    await user.type(screen.getByLabelText('Quota error status'), '429');
    await user.type(screen.getByLabelText('Seed'), '42');
    await user.type(screen.getByLabelText('Succeed first'), '3');
    await user.type(screen.getByLabelText('Fail count'), '10');
    await user.type(screen.getByLabelText('Outage after ms'), '1000');
    await user.type(screen.getByLabelText('Outage duration ms'), '5000');
    await user.type(screen.getByLabelText('Degradation ramp ms'), '10000');
    await user.type(screen.getByLabelText('TTL ms'), '120000');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({
      host: 'full.svc',
      chaos: {
        errorStatus: 503,
        errorProbability: 0.8,
        retryAfter: '60',
        dropConnectionProbability: 0.1,
        latency: { timeUnit: 'MILLISECONDS', value: 200 },
        truncateBodyAtFraction: 0.75,
        malformedBody: true,
        slowResponseChunkSize: 32,
        slowResponseChunkDelay: { timeUnit: 'MILLISECONDS', value: 250 },
        quotaName: 'test-quota',
        quotaLimit: 50,
        quotaWindowMillis: 30000,
        quotaErrorStatus: 429,
        seed: 42,
        succeedFirst: 3,
        failRequestCount: 10,
        outageAfterMillis: 1000,
        outageDurationMillis: 5000,
        degradationRampMillis: 10000,
      },
      ttlMillis: 120000,
    });
  });

  it('shows summary chips for all new fault types from server', async () => {
    stubServiceChaos({
      services: {
        'all-faults.svc': {
          errorStatus: 503,
          errorProbability: 0.5,
          retryAfter: '120',
          dropConnectionProbability: 0.2,
          latency: { timeUnit: 'MILLISECONDS', value: 200 },
          truncateBodyAtFraction: 0.5,
          malformedBody: true,
          slowResponseChunkSize: 64,
          quotaName: 'test',
          quotaLimit: 100,
          quotaWindowMillis: 60000,
          degradationRampMillis: 30000,
          outageAfterMillis: 5000,
          outageDurationMillis: 10000,
          seed: 42,
        },
      },
    });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('all-faults.svc')).toBeInTheDocument());
    expect(screen.getByText('error 503 @ 50% retry-after=120')).toBeInTheDocument();
    expect(screen.getByText('drop @ 20%')).toBeInTheDocument();
    expect(screen.getByText('+200ms latency')).toBeInTheDocument();
    expect(screen.getByText('truncate to 50%')).toBeInTheDocument();
    expect(screen.getByText('malformed body')).toBeInTheDocument();
    expect(screen.getByText('slow response')).toBeInTheDocument();
    expect(screen.getByText('quota 100/60000ms')).toBeInTheDocument();
    expect(screen.getByText('ramp over 30000ms')).toBeInTheDocument();
    expect(screen.getByText('outage window')).toBeInTheDocument();
    expect(screen.getByText('seed 42')).toBeInTheDocument();
  });

  it('validates retry-after requires an error status', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await expandHttp(user);
    await user.type(screen.getByLabelText('Host'), 'bad.svc');
    await user.type(screen.getByLabelText('Retry-After'), '120');
    // Add a valid fault so the "at least one fault" check passes
    await user.type(screen.getByLabelText('Drop prob (0–1)'), '0.5');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText(/Retry-After needs an error status/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });
});
