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
});
