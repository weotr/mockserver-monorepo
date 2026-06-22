/**
 * Tests for the Mocks (Composer) page top-level tab strip:
 * - "Compose" (default) and "Scenarios" tabs are present
 * - The Scenarios tab renders the ScenarioPanel (moved here from the Trace page)
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
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

beforeEach(() => {
  useDashboardStore.setState({ activeExpectations: [] });
  // ScenarioPanel lists scenarios on mount — stub fetch so it resolves to an
  // empty list rather than hitting the network.
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({ ok: true, status: 200, json: async () => ({ scenarios: [] }) })),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('ComposerView Scenarios tab', () => {
  it('shows a Compose tab (default) and a Scenarios tab', () => {
    renderComposer();
    const tabs = screen.getAllByRole('tab');
    expect(tabs).toHaveLength(2);
    expect(tabs[0]).toHaveTextContent('Compose');
    expect(tabs[1]).toHaveTextContent('Scenarios');
    // Compose is the default selected tab.
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
  });

  it('clicking the Scenarios tab renders the ScenarioPanel', async () => {
    const user = userEvent.setup();
    renderComposer();

    // ScenarioPanel not visible while the Compose tab is active.
    expect(screen.queryByText('Scenario State Machine')).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Scenarios' }));

    // ScenarioPanel renders a stable "Scenario State Machine" heading and a
    // "Set State" section — assert on both.
    expect(screen.getByText('Scenario State Machine')).toBeInTheDocument();
    expect(screen.getByText('Set State')).toBeInTheDocument();
  });
});
