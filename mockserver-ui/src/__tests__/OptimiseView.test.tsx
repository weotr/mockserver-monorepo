import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, within, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import OptimiseView from '../components/OptimiseView';
import sample from '../__fixtures__/optimisationReport.sample.json';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderView() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <OptimiseView connectionParams={params} />
    </ThemeProvider>,
  );
}

/**
 * Stub fetch. `markdown` requests resolve to a text body; everything else
 * resolves to the JSON fixture. Returns the mock so tests can inspect the URLs
 * the component actually requested.
 */
function stubFetch(jsonReport: unknown = sample, markdown = '# Optimisation brief\n') {
  const fn = vi.fn(async (url: string) => {
    const isMarkdown = url.includes('format=markdown');
    return {
      ok: true,
      status: 200,
      statusText: 'OK',
      json: async () => jsonReport,
      text: async () => (isMarkdown ? markdown : JSON.stringify(jsonReport)),
    } as unknown as Response;
  });
  vi.stubGlobal('fetch', fn);
  return fn;
}

/** Install a clipboard stub after userEvent.setup (which installs its own). */
function stubClipboard(): { writeText: ReturnType<typeof vi.fn>; restore: () => void } {
  const writeText = vi.fn(async () => undefined);
  const original = Object.getOwnPropertyDescriptor(navigator, 'clipboard');
  Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true, writable: true });
  return {
    writeText,
    restore: () => {
      if (original) Object.defineProperty(navigator, 'clipboard', original);
      else delete (navigator as { clipboard?: unknown }).clipboard;
    },
  };
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('OptimiseView', () => {
  it('fetches the JSON report on mount with format=json', async () => {
    const fetchMock = stubFetch();
    renderView();
    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalled();
    });
    const firstUrl = String(fetchMock.mock.calls[0]?.[0]);
    expect(firstUrl).toContain('/mockserver/llm/optimisationReport');
    expect(firstUrl).toContain('format=json');
  });

  it('renders hero numbers from the report totals', async () => {
    stubFetch();
    renderView();
    const hero = await screen.findByTestId('optimise-hero');
    // total cost ($0.1573), input/output tokens, call count, avg latency
    expect(within(hero).getByText('$0.1573')).toBeInTheDocument();
    expect(within(hero).getByText('48,210')).toBeInTheDocument();
    expect(within(hero).getByText('3,120')).toBeInTheDocument();
    expect(within(hero).getByText('6')).toBeInTheDocument();
    // avg latency = 14230 / 6 ≈ 2371.7 ms → "2.4 s"
    expect(within(hero).getByText('2.4 s')).toBeInTheDocument();
  });

  it('orders signals HIGH severity first', async () => {
    stubFetch();
    renderView();
    const panel = await screen.findByTestId('optimise-signals');
    const chips = within(panel).getAllByText(/^(HIGH|MEDIUM|LOW)$/);
    expect(chips.map((c) => c.textContent)).toEqual(['HIGH', 'MEDIUM', 'LOW']);
  });

  it('shows signal saving and recommendation', async () => {
    stubFetch();
    renderView();
    const panel = await screen.findByTestId('optimise-signals');
    expect(within(panel).getByText(/Identical 5,400-token system prompt/)).toBeInTheDocument();
    expect(within(panel).getByText(/save ~\$0\.0675/)).toBeInTheDocument();
    expect(within(panel).getByText(/Enable provider prompt caching/)).toBeInTheDocument();
  });

  it('renders one table row per call', async () => {
    stubFetch();
    renderView();
    const table = await screen.findByTestId('optimise-call-table');
    // header row + 6 data rows
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(1 + 6);
    // finish reasons from the fixture appear in the table
    expect(within(table).getAllByText('tool_calls').length).toBeGreaterThan(0);
    expect(within(table).getByText('length')).toBeInTheDocument();
  });

  it('Copy optimisation brief fetches format=markdown and writes to clipboard', async () => {
    const fetchMock = stubFetch();
    const user = userEvent.setup();
    const clipboard = stubClipboard();
    try {
      renderView();
      const copyButton = await screen.findByRole('button', { name: /Copy optimisation brief/i });
      await user.click(copyButton);
      await waitFor(() => {
        expect(clipboard.writeText).toHaveBeenCalledWith('# Optimisation brief\n');
      });
      const markdownCall = fetchMock.mock.calls.find((c) => String(c[0]).includes('format=markdown'));
      expect(markdownCall).toBeDefined();
    } finally {
      clipboard.restore();
    }
  });

  it('Download bundle fetches format=json and triggers a download', async () => {
    const fetchMock = stubFetch();
    const user = userEvent.setup();
    const originalCreateObjectURL = URL.createObjectURL;
    const originalRevokeObjectURL = URL.revokeObjectURL;
    URL.createObjectURL = vi.fn(() => 'blob:mock');
    URL.revokeObjectURL = vi.fn();
    const clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    try {
      renderView();
      const downloadButton = await screen.findByRole('button', { name: /Download bundle/i });
      await user.click(downloadButton);
      await waitFor(() => {
        expect(clickSpy).toHaveBeenCalled();
      });
      // download uses the json endpoint; markdown must NOT be requested for a download
      const jsonCalls = fetchMock.mock.calls.filter((c) => String(c[0]).includes('format=json'));
      expect(jsonCalls.length).toBeGreaterThanOrEqual(2); // mount + download
      expect(fetchMock.mock.calls.some((c) => String(c[0]).includes('format=markdown'))).toBe(false);
      expect(URL.createObjectURL).toHaveBeenCalled();
    } finally {
      URL.createObjectURL = originalCreateObjectURL;
      URL.revokeObjectURL = originalRevokeObjectURL;
    }
  });

  it('shows the empty state for the real empty-report wire shape (omitted arrays)', async () => {
    // The server's NON_EMPTY JSON mapper OMITS empty collections entirely, so an
    // empty report arrives with no `calls`, `signals`, `session.providers`,
    // `session.models` or `redaction.*` keys at all. The view must not crash —
    // this is the first thing a user sees before any traffic is captured.
    const emptyWire = {
      schemaVersion: 1,
      generatedBy: 'mockserver',
      session: { key: 'all', groupingBasis: 'PROXY_HOST' },
      totals: {
        callCount: 0, inputTokens: 0, outputTokens: 0, cachedInputTokens: 0,
        reasoningTokens: 0, estimatedCostUsd: 0, costIsEstimated: false,
        totalLatencyMs: 0, toolCallCount: 0,
      },
      redaction: { applied: true },
    };
    stubFetch(emptyWire);
    renderView();
    expect(await screen.findByText('No LLM traffic captured')).toBeInTheDocument();
  });

  it('degrades gracefully on a server error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        text: async () => '',
        json: async () => ({}),
      }) as unknown as Response),
    );
    renderView();
    expect(await screen.findByTestId('optimise-error')).toBeInTheDocument();
    // 404 is humanised to the "feature not available" message
    expect(screen.getByText(/isn.t available on the connected MockServer/i)).toBeInTheDocument();
  });
});
