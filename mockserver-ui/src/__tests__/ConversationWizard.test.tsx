import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ConversationWizard from '../components/ConversationWizard';

function renderWizard(overrides: Partial<Parameters<typeof ConversationWizard>[0]> = {}) {
  const defaults = {
    open: true,
    onClose: vi.fn(),
    connectionParams: { host: 'localhost', port: '1080', secure: false },
    ...overrides,
  };
  return {
    ...render(
      <ThemeProvider theme={buildTheme('dark')}>
        <ConversationWizard {...defaults} />
      </ThemeProvider>,
    ),
    ...defaults,
  };
}

describe('ConversationWizard', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders dialog title', () => {
    renderWizard();
    expect(screen.getByText('New LLM Conversation Mock')).toBeInTheDocument();
  });

  it('shows step 1 with provider and path fields', () => {
    renderWizard();

    expect(screen.getByText('Conversation basics')).toBeInTheDocument();
    expect(screen.getByLabelText('Path')).toBeInTheDocument();
    expect(screen.getByLabelText('Model (optional)')).toBeInTheDocument();
  });

  it('shows 3 steps in the stepper', () => {
    renderWizard();

    expect(screen.getByText('Conversation basics')).toBeInTheDocument();
    expect(screen.getByText('Turns')).toBeInTheDocument();
    expect(screen.getByText('Review')).toBeInTheDocument();
  });

  it('navigates from step 1 to step 2 on Next', async () => {
    const user = userEvent.setup();
    renderWizard();

    // Default path is /v1/messages so Next should be enabled
    await user.click(screen.getByRole('button', { name: 'Next' }));

    // Step 2 should show turn cards
    expect(screen.getByText('Turn 0')).toBeInTheDocument();
    expect(screen.getByText('Match predicates')).toBeInTheDocument();
    expect(screen.getByText('Response')).toBeInTheDocument();
  });

  it('navigates to step 3 and shows review tabs', async () => {
    const user = userEvent.setup();
    renderWizard();

    // Step 1 -> Step 2
    await user.click(screen.getByRole('button', { name: 'Next' }));
    // Step 2 -> Step 3
    await user.click(screen.getByRole('button', { name: 'Next' }));

    // Review step should show Java, JSON, MCP tabs
    expect(screen.getByRole('tab', { name: 'Java' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'JSON' })).toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'MCP' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Register on server' })).toBeInTheDocument();
  });

  it('can navigate back from step 2 to step 1', async () => {
    const user = userEvent.setup();
    renderWizard();

    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Turn 0')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Back' }));
    expect(screen.getByLabelText('Path')).toBeInTheDocument();
  });

  it('calls onClose when Close is clicked', async () => {
    const user = userEvent.setup();
    const { onClose } = renderWizard();

    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledOnce();
  });

  it('can add a turn in step 2', async () => {
    const user = userEvent.setup();
    renderWizard();

    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('Turn 0')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Add turn' }));
    expect(screen.getByText('Turn 1')).toBeInTheDocument();
  });

  it('does not render when open is false', () => {
    renderWizard({ open: false });
    expect(screen.queryByText('New LLM Conversation Mock')).not.toBeInTheDocument();
  });

  it('shows isolation controls on step 1', () => {
    renderWizard();

    expect(screen.getByText('Per-session isolation (optional)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Header' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Query Param' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cookie' })).toBeInTheDocument();
  });

  it('registers conversation via MCP on step 3', async () => {
    const user = userEvent.setup();

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      json: () => Promise.resolve({
        jsonrpc: '2.0',
        id: 1,
        result: {
          content: [{
            type: 'text',
            text: '{"status":"created","count":1,"scenarioName":"__llm_conv_123"}',
          }],
        },
      }),
    }));

    renderWizard();

    // Navigate to step 3
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(screen.getByRole('button', { name: 'Next' }));

    // Click register
    await user.click(screen.getByRole('button', { name: 'Register on server' }));

    expect(fetch).toHaveBeenCalledOnce();
    const fetchCall = (fetch as ReturnType<typeof vi.fn>).mock.calls[0]!;
    const body = JSON.parse(fetchCall[1].body);
    expect(body.params.name).toBe('create_llm_conversation');
    expect(body.params.arguments.provider).toBe('ANTHROPIC');
  });
});
