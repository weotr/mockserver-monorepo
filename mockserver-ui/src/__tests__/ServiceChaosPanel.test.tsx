import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ServiceChaosPanel from '../components/ServiceChaosPanel';

const params = { host: '127.0.0.1', port: '1080', secure: false };

interface PutCall {
  body: Record<string, unknown>;
}

/**
 * Stateful fetch stub: GET returns the current registry snapshot (mutable via
 * the returned `state`), PUT records the call into `puts`.
 */
function stubServiceChaos(initial: {
  services: Record<string, unknown>;
  ttlRemainingMillis?: Record<string, number>;
}) {
  const state = { ...initial };
  const puts: PutCall[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string, init?: RequestInit) => {
      if (init?.method === 'PUT') {
        puts.push({ body: JSON.parse(String(init.body)) as Record<string, unknown> });
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ status: 'ok' }) };
      }
      // The panel also fetches the gRPC-health and TCP-chaos sections on mount; return
      // their own (empty) shapes so they don't get served the serviceChaos snapshot.
      const u = String(url);
      if (u.includes('/grpc/health')) {
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({}) };
      }
      if (u.includes('/tcpChaos')) {
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ hosts: {} }) };
      }
      if (u.includes('/grpcChaos')) {
        return { ok: true, status: 200, statusText: 'ok', json: async () => ({ services: {} }) };
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
    expect(screen.getByText('0 active')).toBeInTheDocument();
  });

  it('registers a host from the form', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: {} });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('No service-scoped chaos registered.')).toBeInTheDocument());

    await user.type(screen.getByLabelText('Host'), 'pay.svc');
    await user.type(screen.getByLabelText('Error status'), '503');
    await user.type(screen.getByLabelText('Error prob'), '0.5');
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

    await user.type(screen.getByLabelText('Host'), 'pay.svc');
    await user.type(screen.getByLabelText('Error prob'), '0.3');
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText(/needs an error status/)).toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('removes a single host', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: { 'a.svc': { errorStatus: 503 } } });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('a.svc')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: 'Remove chaos for a.svc' }));
    await waitFor(() => expect(puts.length).toBeGreaterThan(0));
    expect(puts[0]?.body).toEqual({ host: 'a.svc', remove: true });
  });

  it('clears all registrations', async () => {
    const user = userEvent.setup();
    const { puts } = stubServiceChaos({ services: { 'a.svc': { errorStatus: 503 } } });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('a.svc')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: /Clear all/ }));
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
    stubServiceChaos({ services: { 'a.svc': { errorStatus: 503 }, 'b.svc': { errorStatus: 500 } } });
    render(<ServiceChaosPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByText('a.svc')).toBeInTheDocument());
    const rowA = screen.getByText('a.svc').closest('div');
    expect(rowA).not.toBeNull();
    expect(within(rowA!).getByText('error 503')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove chaos for a.svc' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Remove chaos for b.svc' })).toBeInTheDocument();
  });
});
