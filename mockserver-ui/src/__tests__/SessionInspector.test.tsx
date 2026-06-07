import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import SessionInspector from '../components/SessionInspector';
import { useDashboardStore } from '../store';
import type { JsonListItem } from '../types';

function renderInspector() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <SessionInspector connectionParams={{ host: 'localhost', port: '1080', secure: false }} />
    </ThemeProvider>,
  );
}

function makeAnthropicRequest(
  key: string,
  agentId?: string,
): JsonListItem {
  const headers: Array<{ name: string; values: string[] }> = [
    { name: 'host', values: ['api.anthropic.com'] },
  ];
  if (agentId) {
    headers.push({ name: 'x-agent-id', values: [agentId] });
  }
  return {
    key,
    value: {
      httpRequest: {
        method: 'POST',
        path: '/v1/messages',
        headers,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'claude-sonnet-4-20250514',
            messages: [{ role: 'user', content: 'Hello' }],
          }),
        },
      },
      httpResponse: {
        statusCode: 200,
        body: {
          type: 'JSON',
          json: JSON.stringify({
            model: 'claude-sonnet-4-20250514',
            content: [{ type: 'text', text: 'Hi!' }],
            usage: { input_tokens: 10, output_tokens: 5 },
            stop_reason: 'end_turn',
          }),
        },
      },
    },
  };
}

function makeIsolatedExpectation(scenarioName: string): JsonListItem {
  return {
    key: `exp-${scenarioName}`,
    value: {
      // scenarioName lives at the top level of the expectation payload,
      // matching the real MockServer active-expectation shape.
      scenarioName,
      scenarioState: 'Started',
      newScenarioState: 'turn_1',
      httpLlmResponse: {
        provider: 'ANTHROPIC',
        model: 'claude-sonnet-4-20250514',
        conversationPredicates: { turnIndex: 0 },
        completion: { text: 'Hello!', stopReason: 'end_turn' },
      },
    },
  };
}

describe('SessionInspector', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      activeExpectations: [],
    });
  });

  it('renders empty state when no proxied LLM requests exist', () => {
    renderInspector();
    expect(screen.getByText('No LLM traffic captured yet')).toBeInTheDocument();
    expect(screen.getByText(/proxy through MockServer/)).toBeInTheDocument();
  });

  it('renders sessions when LLM traffic with isolation is present', () => {
    useDashboardStore.setState({
      proxiedRequests: [
        makeAnthropicRequest('req-1', 'agent-A'),
        makeAnthropicRequest('req-2', 'agent-B'),
        makeAnthropicRequest('req-3', 'agent-A'),
      ],
      activeExpectations: [
        makeIsolatedExpectation('__llm_conv_chat__iso=header:x-agent-id'),
      ],
    });

    renderInspector();

    // Should show "Active sessions: 2"
    expect(screen.getByText('Active sessions: 2')).toBeInTheDocument();

    // Session lanes should be visible
    expect(screen.getByText(/chat \/ agent-A/)).toBeInTheDocument();
    expect(screen.getByText(/chat \/ agent-B/)).toBeInTheDocument();
  });

  it('expanding a chip reveals request detail', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        makeAnthropicRequest('req-1', 'agent-A'),
      ],
      activeExpectations: [
        makeIsolatedExpectation('__llm_conv_chat__iso=header:x-agent-id'),
      ],
    });

    renderInspector();

    // Find the request chip and click it
    const chip = screen.getByText(/\[0\] POST \/v1\/messages/);
    await user.click(chip);

    // After expanding, should see conversation content. The AnthropicConversationView
    // shows user messages as bubbles. Check for the content.
    expect(screen.getByText('Hello')).toBeInTheDocument();
  });

  it('search box filters sessions', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        makeAnthropicRequest('req-1', 'agent-A'),
        makeAnthropicRequest('req-2', 'agent-B'),
      ],
      activeExpectations: [
        makeIsolatedExpectation('__llm_conv_chat__iso=header:x-agent-id'),
      ],
    });

    renderInspector();

    // Both sessions visible initially
    expect(screen.getByText(/chat \/ agent-A/)).toBeInTheDocument();
    expect(screen.getByText(/chat \/ agent-B/)).toBeInTheDocument();

    // Type in the search box
    const searchInput = screen.getByPlaceholderText('Filter sessions...');
    await user.type(searchInput, 'agent-A');

    // Only agent-A session should remain
    expect(screen.getByText(/chat \/ agent-A/)).toBeInTheDocument();
    expect(screen.queryByText(/chat \/ agent-B/)).not.toBeInTheDocument();
  });

  it('exposes a Compare tab that switches to the session comparison view', async () => {
    const user = userEvent.setup();
    renderInspector();

    const tabs = screen.getAllByRole('tab');
    expect(tabs[0]).toHaveTextContent('Sessions');
    expect(tabs[1]).toHaveTextContent('Scenarios');
    expect(tabs[2]).toHaveTextContent('Compare');

    await user.click(screen.getByRole('tab', { name: 'Compare' }));

    // The Compare (session comparison) view exposes Run A / Run B selectors.
    expect(screen.getByLabelText('Run A')).toBeInTheDocument();
    expect(screen.getByLabelText('Run B')).toBeInTheDocument();
  });

  it('renders unscoped session for requests without isolation', () => {
    useDashboardStore.setState({
      proxiedRequests: [
        makeAnthropicRequest('req-1'), // no agent id header
      ],
      activeExpectations: [
        makeIsolatedExpectation('__llm_conv_chat__iso=header:x-agent-id'),
      ],
    });

    renderInspector();

    expect(screen.getByText('Unscoped requests')).toBeInTheDocument();
  });
});
