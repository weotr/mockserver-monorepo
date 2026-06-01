/**
 * Tests for the Mocks (Composer) page restructure:
 * - Kind selector shows HTTP, LLM Conversation, gRPC, MCP
 * - MCP kind embeds the derived-tools section
 * - gRPC kind shows the info banner
 * - Store: 'mcp-tools' view migrates to 'composer'
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
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

  it('shows all four kind options', () => {
    renderComposer();
    expect(screen.getByLabelText('HTTP')).toBeInTheDocument();
    expect(screen.getByLabelText('LLM Conversation')).toBeInTheDocument();
    expect(screen.getByLabelText('gRPC')).toBeInTheDocument();
    expect(screen.getByLabelText('MCP')).toBeInTheDocument();
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
