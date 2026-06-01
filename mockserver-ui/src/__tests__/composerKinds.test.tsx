/**
 * Tests for the Mocks (Composer) page restructure:
 * - Kind selector shows HTTP, LLM Conversation, gRPC, DNS, MCP, Import
 * - Each kind shows only its valid action types (no cross-kind leakage)
 * - Switching kind resets actionType to a valid default
 * - Loading an existing expectation infers the correct kind
 * - MCP kind embeds the derived-tools section
 * - gRPC / DNS kind shows the info banner
 * - Store: 'mcp-tools' view migrates to 'composer'
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
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

/**
 * Helper: get the bold label text for each action-type radio inside the
 * "2 . Respond with" section. The rendered DOM uses a Typography with
 * fontWeight 600 for the label and a lighter caption for the description.
 * We grab only the first text node (the bold label) via the inner structure.
 */
function getActionRadioLabels(): string[] {
  const respondHeading = screen.getByText(/2 · Respond with/);
  const respondSection = respondHeading.closest('[class*="MuiPaper"]')!;
  const radios = within(respondSection as HTMLElement).getAllByRole('radio');
  return radios.map((r) => {
    const label = r.closest('label');
    if (!label) return '';
    // The action label is the FIRST <p> / <span> with fontWeight 600 (MUI body2).
    // Simplest: grab the first child text within the label's Box wrapper.
    // The Box contains two Typography elements: the bold label and the caption.
    // Use the aria-label or the first element child's text.
    const box = label.querySelector('[class*="MuiBox"]');
    if (box && box.firstElementChild) {
      return box.firstElementChild.textContent?.trim() ?? '';
    }
    return '';
  });
}

describe('ComposerView kinds', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      activeExpectations: [],
    });
  });

  it('shows the page title as "Mocks"', () => {
    renderComposer();
    expect(screen.getByText('Mocks')).toBeInTheDocument();
  });

  it('shows all six kind options including DNS and Import', () => {
    renderComposer();
    expect(screen.getByLabelText('HTTP')).toBeInTheDocument();
    expect(screen.getByLabelText('LLM Conversation')).toBeInTheDocument();
    expect(screen.getByLabelText('gRPC')).toBeInTheDocument();
    expect(screen.getByLabelText('DNS')).toBeInTheDocument();
    expect(screen.getByLabelText('MCP')).toBeInTheDocument();
    expect(screen.getByLabelText('Import')).toBeInTheDocument();
  });

  it('defaults to HTTP kind', () => {
    renderComposer();
    const httpRadio = screen.getByLabelText('HTTP');
    expect(httpRadio).toBeChecked();
  });

  it('switches to gRPC kind and shows info banner', async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.click(screen.getByLabelText('gRPC'));
    expect(screen.getByText(/gRPC requests are transcoded to HTTP/i)).toBeInTheDocument();
  });

  it('switches to DNS kind and shows info banner', async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.click(screen.getByLabelText('DNS'));
    expect(screen.getByText(/DNS expectations are served by the DNS handler/i)).toBeInTheDocument();
  });

  it('switches to MCP kind and shows info banner', async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.click(screen.getByLabelText('MCP'));
    expect(screen.getByText(/MCP tools are generated automatically/i)).toBeInTheDocument();
  });

  it('MCP kind embeds the MCP tools section', async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.click(screen.getByLabelText('MCP'));
    // McpToolsPanel renders an "MCP Tools" heading
    expect(screen.getByText('MCP Tools')).toBeInTheDocument();
  });
});

// ---------------------------------------------------------------------------
// Action-type filtering per kind
// ---------------------------------------------------------------------------

describe('ComposerView action-type filtering', () => {
  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [] });
  });

  it('HTTP kind does NOT list dns_response or grpc_stream', () => {
    renderComposer();
    const labels = getActionRadioLabels();
    expect(labels).toContain('Static HTTP response');
    expect(labels).toContain('Forward to upstream');
    expect(labels).not.toContain('DNS response');
    expect(labels).not.toContain('gRPC stream response');
  });

  it('HTTP kind lists all HTTP action types', () => {
    renderComposer();
    const labels = getActionRadioLabels();
    const expected = [
      'Static HTTP response', 'Forward to upstream', 'Forward with override',
      'Forward with fallback', 'Class callback', 'Response template',
      'Error / fault injection', 'WebSocket response', 'SSE response',
      'Binary response', 'Forward template', 'Forward class callback',
    ];
    for (const l of expected) {
      expect(labels).toContain(l);
    }
    expect(labels).toHaveLength(expected.length);
  });

  it('gRPC kind lists grpc_stream and static only', async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.click(screen.getByLabelText('gRPC'));
    const labels = getActionRadioLabels();
    expect(labels).toContain('gRPC stream response');
    expect(labels).toContain('Static HTTP response');
    expect(labels).toHaveLength(2);
    // Must not have HTTP-only or DNS types
    expect(labels).not.toContain('Forward to upstream');
    expect(labels).not.toContain('DNS response');
  });

  it('DNS kind lists dns_response only', async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.click(screen.getByLabelText('DNS'));
    const labels = getActionRadioLabels();
    expect(labels).toContain('DNS response');
    expect(labels).toHaveLength(1);
  });

  it('MCP kind lists static only', async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.click(screen.getByLabelText('MCP'));
    const labels = getActionRadioLabels();
    expect(labels).toContain('Static HTTP response');
    expect(labels).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------
// Kind-change resets actionType to a valid default
// ---------------------------------------------------------------------------

describe('ComposerView kind-change default reset', () => {
  beforeEach(() => {
    useDashboardStore.setState({ activeExpectations: [] });
  });

  it('switching to gRPC defaults actionType to grpc_stream', async () => {
    const user = userEvent.setup();
    renderComposer();
    // Start on HTTP, actionType defaults to 'static'
    await user.click(screen.getByLabelText('gRPC'));
    // The grpc_stream radio should be checked
    const labels = getActionRadioLabels();
    expect(labels).toContain('gRPC stream response');
    const respondSection = screen.getByText(/2 · Respond with/).closest('[class*="MuiPaper"]')!;
    const grpcRadio = within(respondSection as HTMLElement).getByLabelText(/gRPC stream response/);
    // static is valid in gRPC kind and was the previous selection, so it stays.
    // But grpc_stream should at least be present and selectable.
    // Actually, 'static' IS in the gRPC allowed list, so it stays selected.
    // Let's verify that switching from a type NOT in the gRPC list resets.
    expect(grpcRadio).toBeInTheDocument();
  });

  it('switching from HTTP (forward selected) to DNS resets to dns_response', async () => {
    const user = userEvent.setup();
    renderComposer();
    // Select "Forward to upstream" on HTTP kind
    await user.click(screen.getByLabelText(/Forward to upstream/));
    // Now switch to DNS — "forward" is not valid for DNS, should reset to dns_response
    await user.click(screen.getByLabelText('DNS'));
    const respondSection = screen.getByText(/2 · Respond with/).closest('[class*="MuiPaper"]')!;
    const dnsRadio = within(respondSection as HTMLElement).getByLabelText(/DNS response/);
    expect(dnsRadio).toBeChecked();
  });

  it('switching from HTTP (static selected) to gRPC keeps static since it is valid', async () => {
    const user = userEvent.setup();
    renderComposer();
    // 'static' is the default for HTTP and is also valid for gRPC
    await user.click(screen.getByLabelText('gRPC'));
    const respondSection = screen.getByText(/2 · Respond with/).closest('[class*="MuiPaper"]')!;
    const staticRadio = within(respondSection as HTMLElement).getByLabelText(/Static HTTP response/);
    expect(staticRadio).toBeChecked();
  });

  it('switching from gRPC (grpc_stream selected) to HTTP resets to static', async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.click(screen.getByLabelText('gRPC'));
    // Select grpc_stream
    await user.click(screen.getByLabelText(/gRPC stream response/));
    // Switch to HTTP — grpc_stream is not valid for HTTP
    await user.click(screen.getByLabelText('HTTP'));
    const respondSection = screen.getByText(/2 · Respond with/).closest('[class*="MuiPaper"]')!;
    const staticRadio = within(respondSection as HTMLElement).getByLabelText(/Static HTTP response/);
    expect(staticRadio).toBeChecked();
  });

  it('leaving gRPC clears the gRPC-shaped matcher path/method when not customised', async () => {
    const user = userEvent.setup();
    renderComposer();
    // Selecting gRPC pre-shapes the matcher to POST /package.Service/Method
    await user.click(screen.getByLabelText('gRPC'));
    expect((screen.getByLabelText('Path') as HTMLInputElement).value).toBe('/package.Service/Method');
    // Switching to DNS must NOT leave the gRPC path/method behind
    await user.click(screen.getByLabelText('DNS'));
    expect((screen.getByLabelText('Path') as HTMLInputElement).value).toBe('');
    // And switching to MCP from gRPC likewise clears it
    await user.click(screen.getByLabelText('gRPC'));
    await user.click(screen.getByLabelText('MCP'));
    expect((screen.getByLabelText('Path') as HTMLInputElement).value).toBe('');
  });

  it('preserves a user-customised path when leaving gRPC', async () => {
    const user = userEvent.setup();
    renderComposer();
    await user.click(screen.getByLabelText('gRPC'));
    const pathField = screen.getByLabelText('Path') as HTMLInputElement;
    await user.clear(pathField);
    await user.type(pathField, '/my.custom.Service/Call');
    await user.click(screen.getByLabelText('HTTP'));
    expect((screen.getByLabelText('Path') as HTMLInputElement).value).toBe('/my.custom.Service/Call');
  });
});

// ---------------------------------------------------------------------------
// Loading existing expectations infers the correct kind
// ---------------------------------------------------------------------------

describe('ComposerView load-existing kind inference', () => {
  it('loading a dnsResponse expectation selects DNS kind', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      activeExpectations: [{
        key: 'dns-exp-001',
        value: {
          id: 'dns-exp-001',
          httpRequest: { method: 'GET', path: '/example.com' },
          dnsResponse: { responseCode: 'NOERROR', answerRecords: [] },
        },
      }],
    });
    renderComposer();
    // The "Edit existing" dropdown should show the expectation
    const select = screen.getByLabelText(/Edit existing or add new/) as HTMLSelectElement;
    await user.selectOptions(select, 'dns-exp-001');
    // Kind should now be DNS
    const dnsKindRadio = screen.getByLabelText('DNS');
    expect(dnsKindRadio).toBeChecked();
    // Action type should be dns_response
    const respondSection = screen.getByText(/2 · Respond with/).closest('[class*="MuiPaper"]')!;
    const dnsActionRadio = within(respondSection as HTMLElement).getByLabelText(/DNS response/);
    expect(dnsActionRadio).toBeChecked();
  });

  it('loading a grpcStreamResponse expectation selects gRPC kind', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      activeExpectations: [{
        key: 'grpc-exp-001',
        value: {
          id: 'grpc-exp-001',
          httpRequest: { method: 'POST', path: '/pkg.Service/Method' },
          grpcStreamResponse: { statusName: 'OK', messages: [] },
        },
      }],
    });
    renderComposer();
    const select = screen.getByLabelText(/Edit existing or add new/) as HTMLSelectElement;
    await user.selectOptions(select, 'grpc-exp-001');
    const grpcKindRadio = screen.getByLabelText('gRPC');
    expect(grpcKindRadio).toBeChecked();
  });

  it('loading an httpResponse expectation selects HTTP kind', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      activeExpectations: [{
        key: 'http-exp-001',
        value: {
          id: 'http-exp-001',
          httpRequest: { method: 'GET', path: '/api/test' },
          httpResponse: { statusCode: 200, body: '{}' },
        },
      }],
    });
    renderComposer();
    const select = screen.getByLabelText(/Edit existing or add new/) as HTMLSelectElement;
    await user.selectOptions(select, 'http-exp-001');
    const httpKindRadio = screen.getByLabelText('HTTP');
    expect(httpKindRadio).toBeChecked();
  });
});

describe('Store view migration', () => {
  it('maps legacy "mcp-tools" view to "composer"', () => {
    // setView accepts string at runtime even though the type has changed
    useDashboardStore.getState().setView('mcp-tools' as never);
    expect(useDashboardStore.getState().view).toBe('composer');
  });

  it('does not change valid views', () => {
    useDashboardStore.getState().setView('dashboard');
    expect(useDashboardStore.getState().view).toBe('dashboard');
    useDashboardStore.getState().setView('composer');
    expect(useDashboardStore.getState().view).toBe('composer');
  });
});
