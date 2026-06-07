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
});
