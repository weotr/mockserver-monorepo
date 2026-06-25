/**
 * W6 — Composer usability tests.
 *
 * Covers the Quick/Advanced mode toggle, the dashboard edit hand-off
 * (pendingEditExpectation), and the humanised register-error path.
 *
 * - Quick mode shows a minimal field set (method + path + status + body +
 *   content-type) and can register a static mock.
 * - Advanced mode reveals the full machinery (kind selector, action radios,
 *   chaos / side-effects / steps / capture sections).
 * - An incoming pendingEditExpectation loads into the form, switches to
 *   Advanced, and is cleared (consumed once).
 * - A humanised register error renders the short message with raw details
 *   behind a "Details" expander.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ComposerView from '../components/ComposerView';
import { useDashboardStore } from '../store';

vi.mock('../lib/mcpClient', () => ({
  buildBaseUrl: () => 'http://127.0.0.1:1080',
  callMcpTool: vi.fn().mockResolvedValue({ ok: true, result: { tools: [], count: 0 } }),
}));

vi.mock('../lib/conversationCodegen', () => ({
  listConversationScenarios: () => [],
}));

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderComposer() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ComposerView connectionParams={params} />
    </ThemeProvider>,
  );
}

function resetStore() {
  useDashboardStore.setState({
    activeExpectations: [],
    pendingEditExpectation: null,
    view: 'composer',
  });
}

describe('ComposerView Quick / Advanced mode', () => {
  beforeEach(() => {
    resetStore();
    try { globalThis.sessionStorage?.clear(); } catch { /* noop */ }
  });

  it('defaults to Quick mode and shows the minimal field set only', async () => {
    renderComposer();
    expect(screen.getByLabelText('Quick mock')).toHaveAttribute('aria-pressed', 'true');
    // Quick card present
    expect(screen.getByTestId('quick-mock-form')).toBeInTheDocument();
    // Minimal fields present
    expect(screen.getByLabelText('Method')).toBeInTheDocument();
    expect(screen.getByLabelText('Path')).toBeInTheDocument();
    expect(screen.getByLabelText('Status code')).toBeInTheDocument();
    expect(screen.getByLabelText('Content-Type')).toBeInTheDocument();
    // Response body is now a Monaco editor (test-setup renders it as a textarea
    // with data-testid="monaco-textarea"); the "Response body" caption labels it.
    // The editor is loaded via a lazy/Suspense split point (JsonEditorLazy keeps
    // monaco out of the main bundle), so it resolves asynchronously — await it.
    expect(await screen.findByText('Response body')).toBeInTheDocument();
    expect(await screen.findByTestId('monaco-textarea')).toBeInTheDocument();
    // Advanced machinery is hidden (the kind selector, action-type radios, and
    // cross-cutting panels). Note "2 · Respond with" is intentionally shared
    // with the Quick card, so it is NOT a discriminator.
    expect(screen.queryByText('Expectation kind')).not.toBeInTheDocument();
    expect(screen.queryByText(/1 · Match a request/)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/Priority/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Inject fault \/ chaos/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Steps pipeline/)).not.toBeInTheDocument();
  });

  it('switching to Advanced reveals the full field set', async () => {
    const user = userEvent.setup({ delay: null });
    renderComposer();
    await user.click(screen.getByLabelText('Advanced'));
    // Kind selector + action radios + cross-cutting sections appear
    expect(screen.getByText('Expectation kind')).toBeInTheDocument();
    expect(screen.getByText(/2 · Respond with/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Priority/)).toBeInTheDocument();
    expect(screen.getByText(/Inject fault \/ chaos/)).toBeInTheDocument();
    expect(screen.getByText(/Before & after actions/)).toBeInTheDocument();
    expect(screen.getByText(/Steps pipeline/)).toBeInTheDocument();
    expect(screen.getByText(/Capture into scenario state/)).toBeInTheDocument();
    // Quick card is gone
    expect(screen.queryByTestId('quick-mock-form')).not.toBeInTheDocument();
  });

  it('Quick mode can register a static mock (PUTs the expectation)', async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('', { status: 201 }),
    );
    renderComposer();

    await user.type(screen.getByLabelText('Path'), '/quick/path');
    await user.clear(screen.getByLabelText('Status code'));
    await user.type(screen.getByLabelText('Status code'), '200');
    // user.type treats { and } as special key syntax — escape them as {{ / }}.
    // The response body is a Monaco editor (mocked as a textarea in test-setup).
    await user.type(screen.getByTestId('monaco-textarea'), '{{"ok":true}}');

    await user.click(screen.getByRole('button', { name: /Register mock/ }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const call = fetchMock.mock.calls[0]!;
    const url = call[0];
    const init = call[1];
    expect(String(url)).toContain('/mockserver/expectation');
    expect(init?.method).toBe('PUT');
    const sentBody = JSON.parse(String(init?.body));
    expect(sentBody.httpRequest.path).toBe('/quick/path');
    expect(sentBody.httpResponse.statusCode).toBe(200);
    fetchMock.mockRestore();
  });

  it('Register button is disabled until a path is entered', () => {
    renderComposer();
    const button = screen.getByRole('button', { name: /Register mock/ });
    expect(button).toBeDisabled();
  });

  it('the inline "Switch to Advanced" link flips the mode', async () => {
    const user = userEvent.setup({ delay: null });
    renderComposer();
    await user.click(screen.getByRole('button', { name: /Switch to Advanced/ }));
    expect(screen.getByText('Expectation kind')).toBeInTheDocument();
  });

  it('a successful register shows a next-step banner with View / Add another', async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('', { status: 201 }),
    );
    renderComposer();
    await user.type(screen.getByLabelText('Path'), '/done');
    await user.click(screen.getByRole('button', { name: /Register mock/ }));

    const banner = await screen.findByTestId('register-success');
    expect(within(banner).getByText(/View on dashboard/)).toBeInTheDocument();
    expect(within(banner).getByText(/Add another/)).toBeInTheDocument();

    // "Add another" resets the form (path cleared) and dismisses the banner
    await user.click(within(banner).getByText(/Add another/));
    expect(screen.queryByTestId('register-success')).not.toBeInTheDocument();
    expect((screen.getByLabelText('Path') as HTMLInputElement).value).toBe('');
    fetchMock.mockRestore();
  });

  it('"View on dashboard" sets the store view to dashboard', async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('', { status: 201 }),
    );
    renderComposer();
    await user.type(screen.getByLabelText('Path'), '/go');
    await user.click(screen.getByRole('button', { name: /Register mock/ }));
    const banner = await screen.findByTestId('register-success');
    await user.click(within(banner).getByText(/View on dashboard/));
    expect(useDashboardStore.getState().view).toBe('dashboard');
    fetchMock.mockRestore();
  });
});

// ---------------------------------------------------------------------------
// Edit hand-off from the dashboard (pendingEditExpectation)
// ---------------------------------------------------------------------------

describe('ComposerView edit hand-off', () => {
  beforeEach(() => {
    resetStore();
    try { globalThis.sessionStorage?.clear(); } catch { /* noop */ }
  });

  it('loads a pending edit into the form, switches to Advanced, and clears it', async () => {
    const exp = {
      id: 'handoff-001',
      httpRequest: { method: 'POST', path: '/handed-off' },
      httpResponse: { statusCode: 418, body: '{"teapot":true}' },
    };
    useDashboardStore.setState({
      activeExpectations: [{ key: 'handoff-001', value: exp }],
      pendingEditExpectation: exp,
    });
    renderComposer();

    // Should switch to Advanced (full form visible)
    await waitFor(() => expect(screen.getByText('Expectation kind')).toBeInTheDocument());
    expect(screen.getByLabelText('Advanced')).toHaveAttribute('aria-pressed', 'true');
    // Form is populated with the handed-off expectation
    expect((screen.getByLabelText(/Expectation ID/) as HTMLInputElement).value).toBe('handoff-001');
    expect((screen.getByLabelText('Path') as HTMLInputElement).value).toBe('/handed-off');
    // The hand-off is consumed exactly once
    expect(useDashboardStore.getState().pendingEditExpectation).toBeNull();
  });

  it('loads a pending edit that is NOT in the active list (direct prefill)', async () => {
    const exp = {
      id: 'not-in-list-001',
      httpRequest: { method: 'GET', path: '/direct' },
      httpResponse: { statusCode: 200, body: '{}' },
    };
    // Note: NOT added to activeExpectations.
    useDashboardStore.setState({
      activeExpectations: [],
      pendingEditExpectation: exp,
    });
    renderComposer();
    await waitFor(() => expect(screen.getByText('Expectation kind')).toBeInTheDocument());
    expect((screen.getByLabelText('Path') as HTMLInputElement).value).toBe('/direct');
    expect(useDashboardStore.getState().pendingEditExpectation).toBeNull();
  });
});

// ---------------------------------------------------------------------------
// Humanised register error
// ---------------------------------------------------------------------------

describe('ComposerView humanised register error', () => {
  beforeEach(() => {
    resetStore();
    try { globalThis.sessionStorage?.clear(); } catch { /* noop */ }
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders a short message with raw text behind a Details expander', async () => {
    const user = userEvent.setup({ delay: null });
    const rawBody = '1 error:\n - field "httpRequest" is required but was missing\n   (long Java stack...)';
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(rawBody, { status: 400 }),
    );
    renderComposer();
    await user.type(screen.getByLabelText('Path'), '/bad');
    await user.click(screen.getByRole('button', { name: /Register mock/ }));

    const errorAlert = await screen.findByTestId('register-error');
    // Short, humanised summary (not the raw wall of text)
    expect(within(errorAlert).getByText(/rejected as invalid/i)).toBeInTheDocument();
    // Raw text is hidden until "Details" is clicked
    expect(within(errorAlert).queryByText(/long Java stack/)).not.toBeInTheDocument();

    await user.click(within(errorAlert).getByRole('button', { name: /Details/ }));
    expect(within(errorAlert).getByText(/long Java stack/)).toBeInTheDocument();
  });

  it('a 404 surfaces the older-version message', async () => {
    const user = userEvent.setup({ delay: null });
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('not found', { status: 404 }),
    );
    renderComposer();
    await user.type(screen.getByLabelText('Path'), '/missing');
    await user.click(screen.getByRole('button', { name: /Register mock/ }));
    const errorAlert = await screen.findByTestId('register-error');
    expect(within(errorAlert).getByText(/isn’t available on the connected MockServer/i)).toBeInTheDocument();
  });
});
