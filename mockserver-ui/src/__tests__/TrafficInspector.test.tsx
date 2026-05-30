import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import TrafficInspector from '../components/TrafficInspector';
import { useDashboardStore } from '../store';

function renderTrafficInspector() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <TrafficInspector />
    </ThemeProvider>,
  );
}

describe('TrafficInspector — ScriptedTurnsPanel wiring', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficIndex: null,
    });
  });

  it('shows Scripted Turns tab when LLM request is selected and conversation expectations exist', async () => {
    const user = userEvent.setup();

    // Set up a proxied LLM request
    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-1',
          value: {
            httpRequest: {
              method: 'POST',
              path: '/v1/messages',
              headers: [{ name: 'host', values: ['api.anthropic.com'] }],
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
                  content: [{ type: 'text', text: 'Hi there!' }],
                  usage: { input_tokens: 5, output_tokens: 3 },
                }),
              },
            },
          },
        },
      ],
      // Two-turn conversation expectations sharing a scenarioName
      activeExpectations: [
        {
          key: 'exp-turn0',
          value: {
            httpLlmResponse: {
              provider: 'ANTHROPIC',
              model: 'claude-sonnet-4-20250514',
              scenarioName: 'weather_conversation',
              scenarioState: 'Started',
              newScenarioState: 'turn_1',
              conversationPredicates: {
                turnIndex: 0,
              },
              completion: {
                text: '',
                toolCalls: [{ name: 'get_weather', arguments: '{"city":"London"}' }],
                stopReason: 'tool_use',
              },
            },
          },
        },
        {
          key: 'exp-turn1',
          value: {
            httpLlmResponse: {
              provider: 'ANTHROPIC',
              model: 'claude-sonnet-4-20250514',
              scenarioName: 'weather_conversation',
              scenarioState: 'turn_1',
              newScenarioState: '__done',
              conversationPredicates: {
                turnIndex: 1,
                containsToolResultFor: 'get_weather',
              },
              completion: {
                text: 'The weather in London is sunny, 22C.',
                stopReason: 'end_turn',
              },
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    // Click on the proxied request row to select it
    // The path shows as "api.anthropic.com/v1/messages" in a single text node
    const row = screen.getByText(/\/v1\/messages/);
    await user.click(row);

    // The Scripted Turns tab should be visible
    expect(screen.getByRole('tab', { name: 'Scripted Turns' })).toBeInTheDocument();

    // Click on the Scripted Turns tab
    await user.click(screen.getByRole('tab', { name: 'Scripted Turns' }));

    // The scripted turns content should be visible
    expect(screen.getByText('Scripted Conversation Turns')).toBeInTheDocument();
    expect(screen.getByText('Turn 0')).toBeInTheDocument();
    expect(screen.getByText('Turn 1')).toBeInTheDocument();
    expect(screen.getByText('Started')).toBeInTheDocument();
    expect(screen.getByText('__done')).toBeInTheDocument();
  });

  it('does not show Scripted Turns tab when no conversation expectations exist', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-1',
          value: {
            httpRequest: {
              method: 'POST',
              path: '/v1/messages',
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
                  content: [{ type: 'text', text: 'Hi!' }],
                  usage: { input_tokens: 3, output_tokens: 1 },
                }),
              },
            },
          },
        },
      ],
      activeExpectations: [],
    });

    renderTrafficInspector();

    const row = screen.getByText(/\/v1\/messages/);
    await user.click(row);

    // No Scripted Turns tab when no conversation expectations
    expect(screen.queryByRole('tab', { name: 'Scripted Turns' })).not.toBeInTheDocument();
  });
});

describe('TrafficInspector — per-request timing display', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      proxiedRequests: [],
      recordedRequests: [],
      activeExpectations: [],
      trafficSearch: '',
      selectedTrafficIndex: null,
    });
  });

  it('shows timing chip in master list when timing data is present on a proxied request', () => {
    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-timed',
          value: {
            httpRequest: {
              method: 'GET',
              path: '/api/data',
              headers: [{ name: 'host', values: ['example.com'] }],
            },
            httpResponse: {
              statusCode: 200,
              timing: {
                connectionTimeInMillis: 12,
                timeToFirstByteInMillis: 85,
                totalTimeInMillis: 142,
                requestStartedMillis: 1700000000000,
                connectionEstablishedMillis: 1700000000012,
                responseReceivedMillis: 1700000000142,
              },
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    // The compact timing label should be visible in the master list row
    expect(screen.getByText('142ms')).toBeInTheDocument();
  });

  it('does not show timing chip when timing data is absent (mocked response)', () => {
    useDashboardStore.setState({
      recordedRequests: [
        {
          key: 'req-mocked',
          value: {
            httpRequest: {
              method: 'GET',
              path: '/api/mocked',
            },
            httpResponse: {
              statusCode: 200,
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    // No timing chip should be present
    expect(screen.queryByText(/\d+ms/)).not.toBeInTheDocument();
  });

  it('shows timing waterfall in detail pane when a timed request is selected', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-timed-detail',
          value: {
            httpRequest: {
              method: 'GET',
              path: '/api/data',
              headers: [{ name: 'host', values: ['example.com'] }],
            },
            httpResponse: {
              statusCode: 200,
              timing: {
                connectionTimeInMillis: 15,
                timeToFirstByteInMillis: 90,
                totalTimeInMillis: 200,
                requestStartedMillis: 1700000000000,
                connectionEstablishedMillis: 1700000000015,
                responseReceivedMillis: 1700000000200,
              },
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    // Click on the row to select it
    const row = screen.getByText(/\/api\/data/);
    await user.click(row);

    // The timing waterfall should appear in the detail pane
    expect(screen.getByTestId('timing-waterfall')).toBeInTheDocument();
    // Timing breakdown chips should be visible
    expect(screen.getByText('connect 15ms')).toBeInTheDocument();
    expect(screen.getByText('TTFB 90ms')).toBeInTheDocument();
    expect(screen.getByText('total 200ms')).toBeInTheDocument();
    // Waterfall bar should be present
    expect(screen.getByTestId('timing-bar')).toBeInTheDocument();
  });

  it('does not show timing waterfall for requests without timing data', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-no-timing',
          value: {
            httpRequest: {
              method: 'GET',
              path: '/api/data',
            },
            httpResponse: {
              statusCode: 200,
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    const row = screen.getByText(/\/api\/data/);
    await user.click(row);

    // No timing waterfall should be shown
    expect(screen.queryByTestId('timing-waterfall')).not.toBeInTheDocument();
  });

  it('shows timing alongside LLM usage detail for proxied LLM traffic', async () => {
    const user = userEvent.setup();

    useDashboardStore.setState({
      proxiedRequests: [
        {
          key: 'req-llm-timed',
          value: {
            httpRequest: {
              method: 'POST',
              path: '/v1/messages',
              headers: [{ name: 'host', values: ['api.anthropic.com'] }],
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
                  content: [{ type: 'text', text: 'Hi!' }],
                  usage: { input_tokens: 5, output_tokens: 2 },
                }),
              },
              timing: {
                connectionTimeInMillis: 8,
                timeToFirstByteInMillis: 1200,
                totalTimeInMillis: 1500,
                requestStartedMillis: 1700000000000,
                connectionEstablishedMillis: 1700000000008,
                responseReceivedMillis: 1700000001500,
              },
            },
          },
        },
      ],
    });

    renderTrafficInspector();

    // Master list should show both the timing chip and the token summary
    expect(screen.getByText('1500ms')).toBeInTheDocument();
    expect(screen.getByText('5 in / 2 out')).toBeInTheDocument();

    // Click to open detail pane
    const row = screen.getByText(/\/v1\/messages/);
    await user.click(row);

    // Both LLM usage and timing waterfall should be visible
    expect(screen.getByTestId('timing-waterfall')).toBeInTheDocument();
    expect(screen.getByText('connect 8ms')).toBeInTheDocument();
    expect(screen.getByText('TTFB 1200ms')).toBeInTheDocument();
    expect(screen.getByText('total 1500ms')).toBeInTheDocument();
  });
});
