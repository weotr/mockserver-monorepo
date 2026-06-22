import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import McpToolsPanel from '../components/McpToolsPanel';

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: vi.fn(),
}));

import { callMcpTool } from '../lib/mcpClient';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <McpToolsPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

describe('McpToolsPanel', () => {
  beforeEach(() => {
    vi.mocked(callMcpTool).mockReset();
  });

  it('renders the generated tools', async () => {
    vi.mocked(callMcpTool).mockResolvedValue({
      ok: true,
      result: {
        tools: [
          {
            name: 'get_users',
            description: 'Mock for GET /users',
            _mockserver: { method: 'GET', path: '/users' },
          },
        ],
        count: 1,
      },
    });
    renderPanel();
    await waitFor(() => expect(screen.getByText('get_users')).toBeInTheDocument());
    expect(screen.getByText('Mock for GET /users')).toBeInTheDocument();
  });

  it('shows an empty state when there are no tools', async () => {
    vi.mocked(callMcpTool).mockResolvedValue({ ok: true, result: { tools: [], count: 0 } });
    renderPanel();
    await waitFor(() => expect(screen.getByText(/No tools/i)).toBeInTheDocument());
  });

  it('shows an error when the tool call fails', async () => {
    vi.mocked(callMcpTool).mockResolvedValue({ ok: false, error: 'boom' });
    renderPanel();
    await waitFor(() => expect(screen.getByText('boom')).toBeInTheDocument());
  });

  it('humanizes a thrown server-shaped error instead of dumping the raw message', async () => {
    vi.mocked(callMcpTool).mockRejectedValue(
      new Error('MockServer returned 404: not found'),
    );
    renderPanel();
    // humanizeServerError maps 404 to the "feature isn't available" guidance,
    // so the raw "MockServer returned 404" string must NOT be shown verbatim.
    await waitFor(() =>
      expect(screen.getByText(/isn.t available on the connected MockServer/i)).toBeInTheDocument(),
    );
    expect(screen.queryByText(/MockServer returned 404/)).not.toBeInTheDocument();
  });

  it('humanizes a network failure (TypeError) into a reachability hint', async () => {
    vi.mocked(callMcpTool).mockRejectedValue(new TypeError('Failed to fetch'));
    renderPanel();
    await waitFor(() =>
      expect(screen.getByText(/Couldn.t reach the MockServer/i)).toBeInTheDocument(),
    );
  });

  it('auto-refreshes the tool list on an interval without a manual click', async () => {
    vi.useFakeTimers();
    try {
      vi.mocked(callMcpTool).mockResolvedValue({ ok: true, result: { tools: [], count: 0 } });
      renderPanel();

      await vi.waitFor(() => expect(callMcpTool).toHaveBeenCalledTimes(1));
      await vi.advanceTimersByTimeAsync(5000);
      await vi.waitFor(() => expect(vi.mocked(callMcpTool).mock.calls.length).toBeGreaterThan(1));
    } finally {
      vi.useRealTimers();
    }
  });
});
