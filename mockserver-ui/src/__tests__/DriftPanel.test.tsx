import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import DriftPanel from '../components/DriftPanel';
import type { DriftResponse } from '../lib/drift';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <DriftPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

function stubFetchDrift(response: DriftResponse) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => response,
    })),
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('DriftPanel', () => {
  it('renders title and description', () => {
    stubFetchDrift({ count: 0, drifts: [] });
    renderPanel();
    expect(screen.getByText('Drift Detection')).toBeInTheDocument();
    expect(screen.getByText(/compares proxied responses against stubs in proxy mode and reports/)).toBeInTheDocument();
  });

  it('shows the empty state when no drifts are detected', async () => {
    stubFetchDrift({ count: 0, drifts: [] });
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('0 detected')).toBeInTheDocument();
    });
    expect(screen.getByText(/No drift detected/)).toBeInTheDocument();
  });

  it('shows the Clear button disabled when count is 0', async () => {
    stubFetchDrift({ count: 0, drifts: [] });
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('0 detected')).toBeInTheDocument();
    });
    const clearButton = screen.getByRole('button', { name: /Clear/i });
    expect(clearButton).toBeDisabled();
  });

  it('renders drift records in a table when populated', async () => {
    stubFetchDrift({
      count: 1,
      drifts: [{
        expectationId: 'exp-abc',
        driftType: 'STATUS',
        field: 'statusCode',
        expectedValue: '200',
        actualValue: '500',
        confidence: 0.92,
        epochTimeMs: 1717000000000,
        semanticSeverity: 'BREAKING',
        semanticExplanation: 'Status changed from 2xx to 5xx',
      }],
    });
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('1 detected')).toBeInTheDocument();
    });

    expect(screen.getByText('exp-abc')).toBeInTheDocument();
    expect(screen.getByText('STATUS')).toBeInTheDocument();
    expect(screen.getByText('statusCode')).toBeInTheDocument();
    expect(screen.getByText('200')).toBeInTheDocument();
    expect(screen.getByText('500')).toBeInTheDocument();
    expect(screen.getByText('92%')).toBeInTheDocument();
    expect(screen.getByText('BREAKING')).toBeInTheDocument();
  });

  it('clears drift records when the Clear button is clicked and confirmed', async () => {
    const user = userEvent.setup();
    let callIndex = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init?: { method?: string }) => {
        // First call: fetch with data. After clear, return empty.
        if (init?.method === 'PUT') {
          return { ok: true, status: 200 };
        }
        callIndex++;
        if (callIndex === 1) {
          return {
            ok: true,
            status: 200,
            json: async () => ({
              count: 2,
              drifts: [{
                expectationId: 'e1', driftType: 'STATUS', field: 'sc',
                confidence: 0.9, epochTimeMs: 1717000000000,
              }, {
                expectationId: 'e2', driftType: 'HEADER_ADDED', field: 'X-New',
                confidence: 0.8, epochTimeMs: 1717000000000,
              }],
            }),
          };
        }
        return {
          ok: true,
          status: 200,
          json: async () => ({ count: 0, drifts: [] }),
        };
      }),
    );

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('2 detected')).toBeInTheDocument();
    });

    const clearButton = screen.getByRole('button', { name: /Clear/i });
    expect(clearButton).not.toBeDisabled();

    // Click Clear — opens confirmation dialog
    await user.click(clearButton);

    // Confirm the destructive action
    await waitFor(() => {
      expect(screen.getByText('Clear all drift records?')).toBeInTheDocument();
    });
    await user.click(screen.getByRole('button', { name: /Clear drift records/i }));

    await waitFor(() => {
      expect(screen.getByText('0 detected')).toBeInTheDocument();
    });
  });

  it('shows an error alert when the fetch fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new Error('Connection refused')),
    );

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText(/Could not load drift records/)).toBeInTheDocument();
    });
    // Humanised network-failure copy rather than the raw exception text.
    expect(screen.getByText(/Couldn’t reach the MockServer/)).toBeInTheDocument();
  });

  it('surfaces a real error on a 500 instead of reporting "no drift"', async () => {
    // A non-OK response used to be swallowed as { count: 0 } — verify the lib now
    // throws and the panel shows the error rather than a misleading empty state.
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 500,
        statusText: 'Internal Server Error',
        json: async () => ({}),
      })),
    );

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText(/Could not load drift records/)).toBeInTheDocument();
    });
    expect(screen.queryByText(/No drift detected/)).toBeInTheDocument(); // table empty, but error shown
    expect(screen.getByText(/internal error|HTTP 500/i)).toBeInTheDocument();
  });

  it('shows the "not available" branch on a 404 (older server)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        json: async () => ({}),
      })),
    );

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('Drift detection not available')).toBeInTheDocument();
    });
    expect(
      screen.getByText(/does not support drift detection/),
    ).toBeInTheDocument();
  });

  it('surfaces an error when Clear fails instead of silently succeeding', async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init?: { method?: string }) => {
        if (init?.method === 'PUT') {
          // Clear endpoint fails.
          return { ok: false, status: 500, statusText: 'Internal Server Error', json: async () => ({}) };
        }
        return {
          ok: true,
          status: 200,
          json: async () => ({
            count: 1,
            drifts: [{ expectationId: 'e1', driftType: 'STATUS', field: 'sc', confidence: 0.9, epochTimeMs: 1717000000000 }],
          }),
        };
      }),
    );

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('1 detected')).toBeInTheDocument();
    });

    await user.click(screen.getByRole('button', { name: /Clear/i }));
    await waitFor(() => {
      expect(screen.getByText('Clear all drift records?')).toBeInTheDocument();
    });
    await user.click(screen.getByRole('button', { name: /Clear drift records/i }));

    await waitFor(() => {
      // The failed clear is reported, not silently treated as success.
      expect(screen.getByText(/internal error|HTTP 500/i)).toBeInTheDocument();
    });
  });

  it('auto-refreshes the drift feed on an interval without a manual click', async () => {
    vi.useFakeTimers();
    try {
      const fetchMock = vi.fn(async () => ({
        ok: true,
        status: 200,
        json: async () => ({ count: 0, drifts: [] }),
      }));
      vi.stubGlobal('fetch', fetchMock);

      renderPanel();

      // initial poll
      await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));

      // advance past the poll interval — the feed re-fetches itself with no user action
      await vi.advanceTimersByTimeAsync(5000);
      await vi.waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(1));
    } finally {
      vi.useRealTimers();
    }
  });

  it('has a Refresh button that triggers a new poll', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ count: 0, drifts: [] }),
    }));
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderPanel();

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalled();
    });
    const callsBefore = fetchMock.mock.calls.length;

    const refreshBtn = screen.getByRole('button', { name: 'Refresh drift' });
    await user.click(refreshBtn);

    await waitFor(() => {
      expect(fetchMock.mock.calls.length).toBeGreaterThan(callsBefore);
    });
  });
});
