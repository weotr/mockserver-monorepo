import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import JsonListItem from '../components/JsonListItem';

describe('JsonListItem', () => {
  it('renders index and description', () => {
    render(
      <JsonListItem
        item={{ key: 'r1', description: 'GET /api/users', value: { method: 'GET', path: '/api/users' } }}
        index={3}
      />,
    );
    expect(screen.getByText('3')).toBeInTheDocument();
    expect(screen.getByText('GET /api/users')).toBeInTheDocument();
  });

  it('does not show JSON body by default', () => {
    const { container } = render(
      <JsonListItem
        item={{ key: 'r1', description: 'GET /test', value: { method: 'GET', path: '/test' } }}
        index={1}
      />,
    );
    expect(container.querySelector('.w-rjv')).not.toBeInTheDocument();
  });

  it('shows JSON body after clicking the header row', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <JsonListItem
        item={{ key: 'r1', description: 'POST /submit', value: { method: 'POST', path: '/submit' } }}
        index={1}
      />,
    );

    await user.click(screen.getByText('POST /submit'));
    expect(container.querySelector('.w-rjv')).toBeInTheDocument();
  });

  it('collapses JSON body when clicked again', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <JsonListItem
        item={{ key: 'r1', description: 'DELETE /item', value: { method: 'DELETE' } }}
        index={1}
      />,
    );

    await user.click(screen.getByText('DELETE /item'));
    expect(container.querySelector('.w-rjv')).toBeInTheDocument();

    await user.click(screen.getByText('DELETE /item'));
    expect(container.querySelector('.w-rjv')).not.toBeInTheDocument();
  });

  it('renders without description', () => {
    render(
      <JsonListItem
        item={{ key: 'r1', value: { path: '/no-desc' } }}
        index={5}
      />,
    );
    expect(screen.getByText('5')).toBeInTheDocument();
  });

  it('renders LLM Response badge when httpLlmResponse is present', () => {
    render(
      <JsonListItem
        item={{
          key: 'llm1',
          description: 'POST /v1/messages',
          value: {
            httpRequest: { method: 'POST', path: '/v1/messages' },
            httpLlmResponse: {
              provider: 'ANTHROPIC',
              model: 'claude-sonnet-4',
              completion: {
                text: 'The capital of France is Paris.',
              },
            },
          },
        }}
        index={1}
      />,
    );
    expect(screen.getByText(/LLM Response/)).toBeInTheDocument();
    expect(screen.getByText(/Anthropic/)).toBeInTheDocument();
    expect(screen.getByText(/claude-sonnet-4/)).toBeInTheDocument();
    expect(screen.getByText(/The capital of France is Paris./)).toBeInTheDocument();
  });

  it('renders LLM Response badge with truncated text preview', () => {
    const longText = 'A'.repeat(100);
    render(
      <JsonListItem
        item={{
          key: 'llm2',
          value: {
            httpLlmResponse: {
              provider: 'OPENAI',
              completion: { text: longText },
            },
          },
        }}
        index={2}
      />,
    );
    expect(screen.getByText(/LLM Response/)).toBeInTheDocument();
    expect(screen.getByText(/OpenAI/)).toBeInTheDocument();
    // The preview should be truncated
    const preview = screen.getByText(/A{10,}/);
    expect(preview.textContent!.length).toBeLessThan(100);
  });

  it('does not render LLM badge for regular expectations', () => {
    render(
      <JsonListItem
        item={{
          key: 'reg1',
          description: 'GET /api/test',
          value: {
            httpRequest: { method: 'GET', path: '/api/test' },
            httpResponse: { statusCode: 200 },
          },
        }}
        index={1}
      />,
    );
    expect(screen.queryByText(/LLM Response/)).not.toBeInTheDocument();
  });

  it('renders streaming chip when httpLlmResponse.streaming is true', () => {
    render(
      <JsonListItem
        item={{
          key: 'llm-stream',
          value: {
            httpLlmResponse: {
              provider: 'ANTHROPIC',
              model: 'claude-sonnet-4',
              streaming: true,
              completion: { text: 'Hello' },
            },
          },
        }}
        index={1}
      />,
    );
    expect(screen.getByText('stream')).toBeInTheDocument();
  });

  it('renders tool count chip when completion has toolCalls', () => {
    render(
      <JsonListItem
        item={{
          key: 'llm-tools',
          value: {
            httpLlmResponse: {
              provider: 'OPENAI',
              completion: {
                text: 'Calling tools',
                toolCalls: [
                  { name: 'search', arguments: '{}' },
                  { name: 'fetch', arguments: '{}' },
                ],
              },
            },
          },
        }}
        index={1}
      />,
    );
    expect(screen.getByText('2 tools')).toBeInTheDocument();
  });

  it('renders embedding chip when embedding field is present', () => {
    render(
      <JsonListItem
        item={{
          key: 'llm-embed',
          value: {
            httpLlmResponse: {
              provider: 'OPENAI',
              embedding: [0.1, 0.2, 0.3],
            },
          },
        }}
        index={1}
      />,
    );
    expect(screen.getByText('embedding')).toBeInTheDocument();
  });

  it('renders stateful chip with turn index when conversationPredicates is present', () => {
    render(
      <JsonListItem
        item={{
          key: 'llm-stateful',
          value: {
            httpLlmResponse: {
              provider: 'ANTHROPIC',
              model: 'claude-sonnet-4',
              conversationPredicates: {
                turnIndex: 1,
                latestMessageContains: 'weather',
              },
              completion: { text: 'It is sunny.' },
            },
          },
        }}
        index={1}
      />,
    );
    expect(screen.getByText('turn 1')).toBeInTheDocument();
  });

  it('renders isolation chip when scenario name has __iso suffix', () => {
    render(
      <JsonListItem
        item={{
          key: 'llm-iso',
          value: {
            httpLlmResponse: {
              provider: 'ANTHROPIC',
              scenarioName: 'conversation_abc__iso=header:x-session-id',
              completion: { text: 'Hello' },
            },
          },
        }}
        index={1}
      />,
    );
    expect(screen.getByText('iso=header:x-session-id')).toBeInTheDocument();
  });

  it('renders all badge chips together when all signals are present', () => {
    render(
      <JsonListItem
        item={{
          key: 'llm-all',
          value: {
            httpLlmResponse: {
              provider: 'ANTHROPIC',
              model: 'claude-sonnet-4',
              streaming: true,
              scenarioName: 'conv__iso=cookie:sid',
              conversationPredicates: { turnIndex: 0 },
              completion: {
                text: 'Hello',
                toolCalls: [{ name: 'search', arguments: '{}' }],
              },
            },
          },
        }}
        index={1}
      />,
    );
    // Four optional signals exist (stream, 1 tool, turn 0, iso) plus the
    // mandatory provider chip. With the 3-chip optional cap, three render
    // directly and the surplus collapses into a "+1 more" chip whose tooltip
    // lists the hidden signal.
    expect(screen.getByText(/LLM Response/)).toBeInTheDocument();
    expect(screen.getByText('stream')).toBeInTheDocument();
    expect(screen.getByText('1 tool')).toBeInTheDocument();
    expect(screen.getByText('turn 0')).toBeInTheDocument();
    expect(screen.getByText('+1 more')).toBeInTheDocument();
  });

  it('renders all five optional chips inline when collapse cap is not exceeded', () => {
    // Three optional chips (stream, 1 tool, embedding) fit within the cap and
    // should all render directly without a "+N more" collapse.
    render(
      <JsonListItem
        item={{
          key: 'llm-three',
          value: {
            httpLlmResponse: {
              provider: 'OPENAI',
              model: 'gpt-4o',
              streaming: true,
              embedding: { dimensions: 1536 },
              completion: {
                toolCalls: [{ name: 'search', arguments: '{}' }],
              },
            },
          },
        }}
        index={1}
      />,
    );
    expect(screen.getByText('stream')).toBeInTheDocument();
    expect(screen.getByText('1 tool')).toBeInTheDocument();
    expect(screen.getByText('embedding')).toBeInTheDocument();
    expect(screen.queryByText(/\+\d+ more/)).not.toBeInTheDocument();
  });

  it('shows chevron right icon when collapsed and expand icon when expanded', async () => {
    const user = userEvent.setup();
    const { container } = render(
      <JsonListItem
        item={{ key: 'r1', description: 'GET /toggle', value: { method: 'GET' } }}
        index={1}
      />,
    );

    expect(container.querySelector('[data-testid="ChevronRightIcon"]')).toBeInTheDocument();
    expect(container.querySelector('[data-testid="ExpandMoreIcon"]')).not.toBeInTheDocument();

    await user.click(screen.getByText('GET /toggle'));
    expect(container.querySelector('[data-testid="ExpandMoreIcon"]')).toBeInTheDocument();
    expect(container.querySelector('[data-testid="ChevronRightIcon"]')).not.toBeInTheDocument();
  });

  // -----------------------------------------------------------------------
  // Chaos profile viewer badge
  // -----------------------------------------------------------------------

  it('renders chaos summary chip when expectation has a chaos block', () => {
    render(
      <JsonListItem
        item={{
          key: 'chaos1',
          value: {
            httpRequest: { method: 'GET', path: '/api/test' },
            httpResponse: { statusCode: 200 },
            chaos: {
              errorStatus: 503,
              errorProbability: 0.5,
            },
          },
        }}
        index={1}
      />,
    );
    expect(screen.getByText(/Chaos:/)).toBeInTheDocument();
    expect(screen.getByText(/503/)).toBeInTheDocument();
    expect(screen.getByText(/50%/)).toBeInTheDocument();
  });

  it('renders chaos chip with latency summary', () => {
    render(
      <JsonListItem
        item={{
          key: 'chaos2',
          value: {
            httpRequest: { method: 'GET', path: '/slow' },
            httpResponse: { statusCode: 200 },
            chaos: {
              latency: { timeUnit: 'SECONDS', value: 3 },
            },
          },
        }}
        index={1}
      />,
    );
    expect(screen.getByText(/Chaos:/)).toBeInTheDocument();
    expect(screen.getByText(/3se/)).toBeInTheDocument();
  });

  it('renders chaos chip with window summary', () => {
    render(
      <JsonListItem
        item={{
          key: 'chaos3',
          value: {
            httpRequest: { method: 'POST', path: '/retry' },
            httpResponse: { statusCode: 200 },
            chaos: {
              errorStatus: 503,
              errorProbability: 1.0,
              succeedFirst: 0,
              failRequestCount: 2,
            },
          },
        }}
        index={1}
      />,
    );
    const chaosChip = screen.getByText(/Chaos:/);
    expect(chaosChip).toBeInTheDocument();
    expect(chaosChip.textContent).toContain('window');
  });

  it('does not render chaos chip when no chaos block', () => {
    render(
      <JsonListItem
        item={{
          key: 'no-chaos',
          value: {
            httpRequest: { method: 'GET', path: '/normal' },
            httpResponse: { statusCode: 200 },
          },
        }}
        index={1}
      />,
    );
    expect(screen.queryByText(/Chaos:/)).not.toBeInTheDocument();
  });
});
