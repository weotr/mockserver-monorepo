import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import CaptureAsMockDialog from '../components/CaptureAsMockDialog';
import type { AnthropicParsed, OpenAiParsed } from '../lib/llmTraffic';

function renderDialog(overrides: Partial<Parameters<typeof CaptureAsMockDialog>[0]> = {}) {
  const defaults = {
    open: true,
    onClose: vi.fn(),
    parsed: {
      kind: 'anthropic' as const,
      model: 'claude-sonnet-4-20250514',
      stream: false,
      messages: [],
      system: null,
      tools: null,
      maxTokens: null,
      responseContent: [{ type: 'text', text: 'Hello from Claude' }],
      usage: null,
      stopReason: 'end_turn',
      sseEvents: null,
      streamed: false,
      streamTruncated: false,
    } satisfies AnthropicParsed,
    path: '/v1/messages',
    connectionParams: { host: 'localhost', port: '1080', secure: false },
    ...overrides,
  };
  return { ...render(
    <ThemeProvider theme={buildTheme('dark')}>
      <CaptureAsMockDialog {...defaults} />
    </ThemeProvider>,
  ), ...defaults };
}

describe('CaptureAsMockDialog', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders dialog title', () => {
    renderDialog();
    expect(screen.getByText('Capture as Mock')).toBeInTheDocument();
  });

  it('shows editable fields on Edit tab', () => {
    renderDialog();

    // Path field
    expect(screen.getByLabelText('Path')).toHaveValue('/v1/messages');
    // Model field
    expect(screen.getByLabelText('Model')).toHaveValue('claude-sonnet-4-20250514');
    // Text field
    expect(screen.getByLabelText('Text')).toHaveValue('Hello from Claude');
    // Stop Reason field
    expect(screen.getByLabelText('Stop Reason')).toHaveValue('end_turn');
  });

  it('shows Register and Cancel buttons', () => {
    renderDialog();

    expect(screen.getByRole('button', { name: 'Register' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
  });

  it('calls onClose when Cancel is clicked', async () => {
    const user = userEvent.setup();
    const { onClose } = renderDialog();

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('switches to Copy as JSON tab and shows JSON output', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole('tab', { name: 'Copy as JSON' }));

    // Should show the JSON representation
    expect(screen.getByText(/httpLlmResponse/)).toBeInTheDocument();
    expect(screen.getByText(/ANTHROPIC/)).toBeInTheDocument();
  });

  it('switches to Copy as Java tab and shows Java output', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByRole('tab', { name: 'Copy as Java' }));

    // Should show the Java representation
    expect(screen.getByText(/import static org.mockserver.client.Llm.\*/)).toBeInTheDocument();
    expect(screen.getByText(/withProvider\(Provider.ANTHROPIC\)/)).toBeInTheDocument();
  });

  it('populates fields from OpenAI traffic', () => {
    const openAiParsed: OpenAiParsed = {
      kind: 'openai',
      model: 'gpt-4o',
      stream: true,
      messages: [],
      tools: null,
      choices: [
        {
          message: { role: 'assistant', content: 'OpenAI response' },
          finish_reason: 'stop',
        },
      ],
      usage: null,
      sseEvents: null,
      streamed: false,
      streamTruncated: false,
    };

    renderDialog({ parsed: openAiParsed, path: '/chat/completions' });

    expect(screen.getByLabelText('Path')).toHaveValue('/chat/completions');
    expect(screen.getByLabelText('Model')).toHaveValue('gpt-4o');
    expect(screen.getByLabelText('Text')).toHaveValue('OpenAI response');
    expect(screen.getByLabelText('Stop Reason')).toHaveValue('stop');
  });

  it('allows adding a tool call', async () => {
    const user = userEvent.setup();
    renderDialog();

    await user.click(screen.getByLabelText('Add tool call'));

    // Should see Name and Arguments fields for the new tool call
    expect(screen.getByLabelText('Name')).toBeInTheDocument();
    expect(screen.getByLabelText('Arguments')).toBeInTheDocument();
  });

  it('does not render when open is false', () => {
    renderDialog({ open: false });
    expect(screen.queryByText('Capture as Mock')).not.toBeInTheDocument();
  });

  it('calls MCP endpoint on Register click', async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();

    // Mock successful MCP response
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        jsonrpc: '2.0',
        id: 1,
        result: {
          content: [{ type: 'text', text: '{"status":"created","count":1}' }],
        },
      }),
    }));

    renderDialog({ onClose });

    await user.click(screen.getByRole('button', { name: 'Register' }));

    // Wait for the async operation
    expect(fetch).toHaveBeenCalledOnce();
    const fetchCall = (fetch as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(fetchCall[0]).toBe('http://localhost:1080/mockserver/mcp');

    const body = JSON.parse(fetchCall[1].body);
    expect(body.params.name).toBe('mock_llm_completion');
    expect(body.params.arguments.provider).toBe('ANTHROPIC');
  });
});
