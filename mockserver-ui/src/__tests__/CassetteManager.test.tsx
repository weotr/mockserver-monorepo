import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import CassetteManager from '../components/CassetteManager';
import { clearCassettes, addCassette } from '../lib/cassetteRegistry';
import { _clearMcpSessionCache } from '../lib/mcpClient';

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

const store: Record<string, string> = {};

beforeEach(() => {
  for (const key of Object.keys(store)) {
    delete store[key];
  }
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => store[key] ?? null,
    setItem: (key: string, value: string) => { store[key] = value; },
    removeItem: (key: string) => { delete store[key]; },
  });
  clearCassettes();
  vi.restoreAllMocks();
  _clearMcpSessionCache();
  // Default fetch stub so CassetteManagerBody's mount-time server-cassette fetch is hermetic
  // (returns an empty list) rather than hitting a real MockServer that may be running on the
  // default localhost:1080. Tests that need specific responses re-stub fetch, overriding this.
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: true,
    headers: { get: () => null },
    json: () => Promise.resolve({ cassettes: [] }),
  }));
});

// Find the MCP `tools/call` request among all fetch calls. The cassette manager also issues
// cassette-registry requests (a GET on mount, a best-effort PUT after record/load), so the
// tools/call is no longer at a fixed index.
function findMcpToolCall(): [string, { body: string }] {
  const calls = (fetch as ReturnType<typeof vi.fn>).mock.calls as [string, { body?: string }][];
  const match = calls.find(
    (c) => c[0] === 'http://localhost:1080/mockserver/mcp'
      && typeof c[1]?.body === 'string'
      && c[1].body.includes('"tools/call"'),
  );
  if (!match) throw new Error('no MCP tools/call fetch found');
  return match as [string, { body: string }];
}

function renderDialog(overrides: Partial<Parameters<typeof CassetteManager>[0]> = {}) {
  const defaults = {
    open: true,
    onClose: vi.fn(),
    connectionParams: { host: 'localhost', port: '1080', secure: false },
    ...overrides,
  };
  return {
    ...render(
      <ThemeProvider theme={buildTheme('dark')}>
        <CassetteManager {...defaults} />
      </ThemeProvider>,
    ),
    ...defaults,
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('CassetteManager', () => {
  it('renders dialog title', () => {
    renderDialog();
    expect(screen.getByText('Cassette Manager')).toBeInTheDocument();
  });

  it('does not render when open is false', () => {
    renderDialog({ open: false });
    expect(screen.queryByText('Cassette Manager')).not.toBeInTheDocument();
  });

  it('shows empty state on List tab when no cassettes exist', () => {
    renderDialog();
    expect(screen.getByText('No cassettes tracked yet')).toBeInTheDocument();
  });

  it('shows cassettes on List tab', () => {
    addCassette('/tmp/test-fixture.json', 5, 'recorded');
    renderDialog();
    expect(screen.getByText('test-fixture.json')).toBeInTheDocument();
  });

  it('switches to Record tab and shows form', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole('tab', { name: 'Record' }));

    expect(screen.getByLabelText(/File path \(required\)/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Request path filter/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Host filter/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Record' })).toBeInTheDocument();
  });

  it('Record button is disabled when path is empty', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole('tab', { name: 'Record' }));

    const recordBtn = screen.getByRole('button', { name: 'Record' });
    expect(recordBtn).toBeDisabled();
  });

  it('switches to Load tab and shows form', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole('tab', { name: 'Load' }));

    expect(screen.getByLabelText(/File path \(required\)/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Load Expectations' })).toBeInTheDocument();
  });

  it('Load button is disabled when path is empty', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole('tab', { name: 'Load' }));

    const loadBtn = screen.getByRole('button', { name: 'Load Expectations' });
    expect(loadBtn).toBeDisabled();
  });

  it('switches to Export tab and shows empty state', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole('tab', { name: 'Export' }));

    expect(screen.getByText('No cassettes available to export')).toBeInTheDocument();
  });

  it('calls onClose when Close button is clicked', async () => {
    const user = userEvent.setup();
    const { onClose } = renderDialog();

    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('calls MCP endpoint on Record with correct tool name and args', async () => {
    const user = userEvent.setup();

    // The MCP client now performs initialize + notifications/initialized
    // before tools/call. Same mock response works for all three since only
    // headers (init) / ok (notify) / body (tool) are consumed in turn.
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'test-session' },
      json: () => Promise.resolve({
        jsonrpc: '2.0',
        id: 1,
        result: {
          content: [{ type: 'text', text: '{"status":"written","count":3,"file":"/tmp/out.json","message":"Wrote 3 expectations"}' }],
        },
      }),
    }));

    renderDialog();

    await user.click(screen.getByRole('tab', { name: 'Record' }));

    const pathInput = screen.getByLabelText(/File path \(required\)/);
    await user.type(pathInput, '/tmp/out.json');

    const recordBtn = screen.getByRole('button', { name: 'Record' });
    await user.click(recordBtn);

    // Locate the MCP tools/call among all fetch calls — there are also cassette-registry calls
    // (a GET on mount + a best-effort PUT mirroring the recording server-side).
    const toolCall = findMcpToolCall();
    expect(toolCall[0]).toBe('http://localhost:1080/mockserver/mcp');

    const body = JSON.parse(toolCall[1].body as string) as Record<string, unknown>;
    const params = body['params'] as Record<string, unknown>;
    expect(params['name']).toBe('record_llm_fixtures');
    const args = params['arguments'] as Record<string, unknown>;
    expect(args['path']).toBe('/tmp/out.json');
  });

  it('calls MCP endpoint on Load with correct tool name and args', async () => {
    const user = userEvent.setup();

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      headers: { get: () => 'test-session' },
      json: () => Promise.resolve({
        jsonrpc: '2.0',
        id: 1,
        result: {
          content: [{ type: 'text', text: '{"status":"loaded","count":5,"message":"Loaded 5 expectations"}' }],
        },
      }),
    }));

    renderDialog();

    await user.click(screen.getByRole('tab', { name: 'Load' }));

    const pathInput = screen.getByLabelText(/File path \(required\)/);
    await user.type(pathInput, '/tmp/fixture.json');

    const loadBtn = screen.getByRole('button', { name: 'Load Expectations' });
    await user.click(loadBtn);

    const toolCall = findMcpToolCall();
    const body = JSON.parse(toolCall[1].body as string) as Record<string, unknown>;
    const params = body['params'] as Record<string, unknown>;
    expect(params['name']).toBe('load_expectations_from_file');
    const args = params['arguments'] as Record<string, unknown>;
    expect(args['path']).toBe('/tmp/fixture.json');
  });

  it('removes a cassette from the list', async () => {
    const user = userEvent.setup();

    addCassette('/tmp/test.json', 5, 'recorded');
    renderDialog();

    expect(screen.getByText('test.json')).toBeInTheDocument();

    const removeBtn = screen.getByLabelText('Remove test.json');
    await user.click(removeBtn);

    expect(screen.queryByText('test.json')).not.toBeInTheDocument();
    expect(screen.getByText('No cassettes tracked yet')).toBeInTheDocument();
  });
});
