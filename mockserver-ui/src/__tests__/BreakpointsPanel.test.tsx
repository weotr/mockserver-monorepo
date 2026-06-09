import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import BreakpointsPanel from '../components/BreakpointsPanel';
import type { BreakpointListResponse } from '../lib/breakpoints';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <BreakpointsPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

function stubFetchBreakpoints(response: BreakpointListResponse) {
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

describe('BreakpointsPanel', () => {
  it('renders title and description', () => {
    stubFetchBreakpoints({ pausedExchanges: [], count: 0 });
    renderPanel();
    expect(screen.getByText('Breakpoints')).toBeInTheDocument();
    expect(screen.getByText(/Requests paused by breakpoint expectations/)).toBeInTheDocument();
  });

  it('shows the empty state when no paused exchanges exist', async () => {
    stubFetchBreakpoints({ pausedExchanges: [], count: 0 });
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('0 paused')).toBeInTheDocument();
    });
    expect(screen.getByText(/No paused requests/)).toBeInTheDocument();
  });

  it('renders paused exchanges in a table when populated', async () => {
    stubFetchBreakpoints({
      count: 1,
      pausedExchanges: [{
        id: 'abc-123',
        ageMillis: 5000,
        expectationId: 'exp-1',
        request: { method: 'GET', path: '/api/users' },
      }],
    });
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('1 paused')).toBeInTheDocument();
    });

    expect(screen.getByText('GET')).toBeInTheDocument();
    expect(screen.getByText('/api/users')).toBeInTheDocument();
    expect(screen.getByText('5s')).toBeInTheDocument();
    expect(screen.getByText('abc-123')).toBeInTheDocument();
    expect(screen.getByText('exp-1')).toBeInTheDocument();
  });

  it('calls continue endpoint when Continue button is clicked', async () => {
    const user = userEvent.setup();
    let callIndex = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init?: RequestInit) => {
        if (init?.method === 'PUT') {
          return { ok: true, status: 200, json: async () => ({ status: 'continued', id: 'abc-123' }) };
        }
        callIndex++;
        if (callIndex === 1) {
          return {
            ok: true,
            status: 200,
            json: async () => ({
              count: 1,
              pausedExchanges: [{
                id: 'abc-123',
                ageMillis: 2000,
                request: { method: 'POST', path: '/test' },
              }],
            }),
          };
        }
        return {
          ok: true,
          status: 200,
          json: async () => ({ count: 0, pausedExchanges: [] }),
        };
      }),
    );

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('1 paused')).toBeInTheDocument();
    });

    const continueBtn = screen.getByRole('button', { name: /Continue abc-123/ });
    await user.click(continueBtn);

    // Verify the PUT was called with the right payload
    const fetchMock = vi.mocked(globalThis.fetch);
    const putCall = fetchMock.mock.calls.find(
      ([u, i]) => String(u).includes('/breakpoint/continue') && (i as RequestInit)?.method === 'PUT',
    );
    expect(putCall).toBeDefined();
    const putBody = JSON.parse((putCall![1] as RequestInit).body as string);
    expect(putBody).toEqual({ id: 'abc-123' });

    // After action, list should refresh
    await waitFor(() => {
      expect(screen.getByText('0 paused')).toBeInTheDocument();
    });
  });

  it('calls abort endpoint when Abort button is clicked', async () => {
    const user = userEvent.setup();
    let callIndex = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init?: RequestInit) => {
        if (init?.method === 'PUT') {
          return { ok: true, status: 200, json: async () => ({ status: 'aborted', id: 'abc-123' }) };
        }
        callIndex++;
        if (callIndex === 1) {
          return {
            ok: true,
            status: 200,
            json: async () => ({
              count: 1,
              pausedExchanges: [{
                id: 'abc-123',
                ageMillis: 3000,
                request: { method: 'DELETE', path: '/remove' },
              }],
            }),
          };
        }
        return {
          ok: true,
          status: 200,
          json: async () => ({ count: 0, pausedExchanges: [] }),
        };
      }),
    );

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('1 paused')).toBeInTheDocument();
    });

    const abortBtn = screen.getByRole('button', { name: /Abort abc-123/ });
    await user.click(abortBtn);

    const fetchMock = vi.mocked(globalThis.fetch);
    const putCall = fetchMock.mock.calls.find(
      ([u, i]) => String(u).includes('/breakpoint/abort') && (i as RequestInit)?.method === 'PUT',
    );
    expect(putCall).toBeDefined();
    const putBody = JSON.parse((putCall![1] as RequestInit).body as string);
    expect(putBody).toEqual({ id: 'abc-123' });
  });

  it('opens modify dialog and sends modified request on submit', async () => {
    const user = userEvent.setup();
    let callIndex = 0;
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string, init?: RequestInit) => {
        if (init?.method === 'PUT') {
          return { ok: true, status: 200, json: async () => ({ status: 'modified', id: 'abc-123' }) };
        }
        callIndex++;
        if (callIndex === 1) {
          return {
            ok: true,
            status: 200,
            json: async () => ({
              count: 1,
              pausedExchanges: [{
                id: 'abc-123',
                ageMillis: 1000,
                request: { method: 'GET', path: '/original' },
              }],
            }),
          };
        }
        return {
          ok: true,
          status: 200,
          json: async () => ({ count: 0, pausedExchanges: [] }),
        };
      }),
    );

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('1 paused')).toBeInTheDocument();
    });

    // Open the modify dialog
    const modifyBtn = screen.getByRole('button', { name: /Modify abc-123/ });
    await user.click(modifyBtn);

    // Dialog should open with pre-filled JSON
    await waitFor(() => {
      expect(screen.getByText('Modify Request')).toBeInTheDocument();
    });

    // The textarea should contain the request JSON
    const textarea = screen.getByRole('textbox');
    expect(textarea).toHaveValue(JSON.stringify({ method: 'GET', path: '/original' }, null, 2));

    // Set new JSON value (fireEvent.change avoids userEvent's special character handling for braces)
    fireEvent.change(textarea, { target: { value: '{"method":"POST","path":"/modified"}' } });

    // Submit
    const sendBtn = screen.getByRole('button', { name: /Send Modified/ });
    await user.click(sendBtn);

    const fetchMock = vi.mocked(globalThis.fetch);
    const putCall = fetchMock.mock.calls.find(
      ([u, i]) => String(u).includes('/breakpoint/modify') && (i as RequestInit)?.method === 'PUT',
    );
    expect(putCall).toBeDefined();
    const putBody = JSON.parse((putCall![1] as RequestInit).body as string);
    expect(putBody.id).toBe('abc-123');
    expect(putBody.httpRequest).toEqual({ method: 'POST', path: '/modified' });
  });

  it('shows an error alert when the fetch fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new Error('Connection refused')),
    );

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText(/Could not load paused exchanges/)).toBeInTheDocument();
    });
    expect(screen.getByText('Connection refused')).toBeInTheDocument();
  });

  it('has a Refresh button that triggers a new poll', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => ({ pausedExchanges: [], count: 0 }),
    }));
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderPanel();

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalled();
    });
    const callsBefore = fetchMock.mock.calls.length;

    const refreshBtn = screen.getByRole('button', { name: 'Refresh breakpoints' });
    await user.click(refreshBtn);

    await waitFor(() => {
      expect(fetchMock.mock.calls.length).toBeGreaterThan(callsBefore);
    });
  });

  it('shows modify dialog validation error for invalid JSON', async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: true,
        status: 200,
        json: async () => ({
          count: 1,
          pausedExchanges: [{
            id: 'abc-123',
            ageMillis: 1000,
            request: { method: 'GET', path: '/test' },
          }],
        }),
      })),
    );

    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('1 paused')).toBeInTheDocument();
    });

    const modifyBtn = screen.getByRole('button', { name: /Modify abc-123/ });
    await user.click(modifyBtn);

    await waitFor(() => {
      expect(screen.getByText('Modify Request')).toBeInTheDocument();
    });

    const textarea = screen.getByRole('textbox');
    fireEvent.change(textarea, { target: { value: 'not valid json' } });

    const sendBtn = screen.getByRole('button', { name: /Send Modified/ });
    await user.click(sendBtn);

    await waitFor(() => {
      expect(screen.getByText('Invalid JSON')).toBeInTheDocument();
    });
  });
});
