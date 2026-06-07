import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import AsyncApiPanel from '../components/AsyncApiPanel';

const params = { host: '127.0.0.1', port: '1080', secure: false };

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AsyncApiPanel', () => {
  it('shows unavailable warning when module returns 501', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ status: 501 }),
    );

    render(<AsyncApiPanel connectionParams={params} />);

    await waitFor(() => {
      expect(screen.getByText(/Module unavailable/i)).toBeInTheDocument();
    });
  });

  it('shows empty state when no spec is loaded', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ loaded: false, channels: [], recordedMessages: [] }),
      }),
    );

    render(<AsyncApiPanel connectionParams={params} />);

    await waitFor(() => {
      expect(screen.getByText(/No channels loaded/)).toBeInTheDocument();
    });
  });

  it('displays loaded channels from the status response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({
          loaded: true,
          specTitle: 'Orders API',
          specVersion: '2.6.0',
          channels: [
            { name: 'orders', hasSchema: true, exampleCount: 2 },
            { name: 'events', hasSchema: false, exampleCount: 0 },
          ],
          publishers: 1,
          subscribers: 1,
          recordedMessages: [],
        }),
      }),
    );

    render(<AsyncApiPanel connectionParams={params} />);

    await waitFor(() => {
      expect(screen.getByText('orders')).toBeInTheDocument();
      expect(screen.getByText('events')).toBeInTheDocument();
      expect(screen.getByText(/Orders API/)).toBeInTheDocument();
    });
  });

  it('renders recorded messages', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({
          loaded: true,
          specTitle: 'Test',
          specVersion: '3.0.0',
          channels: [{ name: 'orders', hasSchema: true, exampleCount: 1 }],
          publishers: 1,
          subscribers: 1,
          recordedMessages: [
            {
              channel: 'orders',
              key: 'order-42',
              payload: '{"orderId":42}',
              headers: { 'trace-id': 'abc' },
              timestamp: '2024-01-01T00:00:00Z',
              schemaValid: true,
            },
          ],
        }),
      }),
    );

    render(<AsyncApiPanel connectionParams={params} />);

    await waitFor(() => {
      expect(screen.getByText('order-42')).toBeInTheDocument();
      expect(screen.getByText('{"orderId":42}')).toBeInTheDocument();
    });
  });

  it('shows connected chip when spec is loaded', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({
          loaded: true,
          specTitle: 'API',
          specVersion: '2.0.0',
          channels: [],
          publishers: 0,
          subscribers: 0,
          recordedMessages: [],
        }),
      }),
    );

    render(<AsyncApiPanel connectionParams={params} />);

    await waitFor(() => {
      expect(screen.getByText('connected')).toBeInTheDocument();
    });
  });

  it('shows error alert when fetch fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new Error('Network error')),
    );

    render(<AsyncApiPanel connectionParams={params} />);

    await waitFor(() => {
      expect(screen.getByText(/Network error/)).toBeInTheDocument();
    });
  });
});
