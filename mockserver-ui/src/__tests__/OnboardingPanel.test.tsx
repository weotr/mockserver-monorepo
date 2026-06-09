import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import OnboardingPanel from '../components/OnboardingPanel';
import { useDashboardStore } from '../store';
import type { WebSocketMessage } from '../types';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <OnboardingPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

beforeEach(() => {
  useDashboardStore.setState({
    logMessages: [],
    activeExpectations: [],
    recordedRequests: [],
    proxiedRequests: [],
    view: 'get-started',
    requestFilter: {},
    filterEnabled: false,
    filterExpanded: false,
    connectionStatus: 'disconnected',
    autoScroll: true,
    logSearch: '',
    expectationSearch: '',
    receivedSearch: '',
    proxiedSearch: '',
    trafficSearch: '',
    error: null,
    notification: null,
  });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('OnboardingPanel', () => {
  it('renders the welcome heading', () => {
    renderPanel();
    expect(screen.getByText('Welcome to MockServer')).toBeInTheDocument();
  });

  it('renders all four action cards', () => {
    renderPanel();
    expect(screen.getByText('Import an OpenAPI Spec')).toBeInTheDocument();
    expect(screen.getByText('Record Live Traffic')).toBeInTheDocument();
    expect(screen.getByText('Try a Quick-Start Recipe')).toBeInTheDocument();
    expect(screen.getByText('Explore the Dashboard')).toBeInTheDocument();
  });

  it('renders the Import OpenAPI button', () => {
    renderPanel();
    expect(screen.getByRole('button', { name: /Import OpenAPI/i })).toBeInTheDocument();
  });

  it('renders external links to documentation', () => {
    renderPanel();
    const proxyLink = screen.getByRole('link', { name: /Proxy setup guide/i });
    expect(proxyLink).toHaveAttribute('href', 'https://www.mock-server.com/mock_server/self_hosting_mockserver.html');
    expect(proxyLink).toHaveAttribute('target', '_blank');

    const recipesLink = screen.getByRole('link', { name: /View recipes/i });
    expect(recipesLink).toHaveAttribute('href', expect.stringContaining('examples/docker-compose'));

    const docsLink = screen.getByRole('link', { name: /Dashboard docs/i });
    expect(docsLink).toHaveAttribute('href', 'https://www.mock-server.com/mock_server/mockserver_ui.html');
  });

  it('opens the OpenAPI import dialog when the button is clicked', async () => {
    const user = userEvent.setup();
    renderPanel();

    await user.click(screen.getByRole('button', { name: /Import OpenAPI/i }));

    // The dialog should appear (OpenApiImportDialog renders with a dialog title)
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });
});

describe('OnboardingPanel default-selection logic', () => {
  it('is the default view when the store initialises', () => {
    expect(useDashboardStore.getState().view).toBe('get-started');
  });

  it('stays on get-started when applyMessage has no data', () => {
    const message: WebSocketMessage = {
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [],
    };
    useDashboardStore.getState().applyMessage(message);
    expect(useDashboardStore.getState().view).toBe('get-started');
  });

  it('auto-switches to dashboard when expectations arrive', () => {
    const message: WebSocketMessage = {
      logMessages: [],
      activeExpectations: [{ key: 'exp1', value: { httpRequest: { path: '/test' } } }],
      recordedRequests: [],
      proxiedRequests: [],
    };
    useDashboardStore.getState().applyMessage(message);
    expect(useDashboardStore.getState().view).toBe('dashboard');
  });

  it('auto-switches to dashboard when recorded requests arrive', () => {
    const message: WebSocketMessage = {
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [{ key: 'rec1', value: { path: '/received' } }],
      proxiedRequests: [],
    };
    useDashboardStore.getState().applyMessage(message);
    expect(useDashboardStore.getState().view).toBe('dashboard');
  });

  it('auto-switches to dashboard when proxied requests arrive', () => {
    const message: WebSocketMessage = {
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [{ key: 'prx1', value: { path: '/proxied' } }],
    };
    useDashboardStore.getState().applyMessage(message);
    expect(useDashboardStore.getState().view).toBe('dashboard');
  });

  it('does not auto-switch when view has been manually changed', () => {
    useDashboardStore.getState().setView('traffic');
    const message: WebSocketMessage = {
      logMessages: [],
      activeExpectations: [{ key: 'exp1', value: {} }],
      recordedRequests: [],
      proxiedRequests: [],
    };
    useDashboardStore.getState().applyMessage(message);
    expect(useDashboardStore.getState().view).toBe('traffic');
  });

  it('does not auto-switch when log messages arrive without other data', () => {
    const message: WebSocketMessage = {
      logMessages: [{ key: 'log1', value: { messageParts: [] } }],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [],
    };
    useDashboardStore.getState().applyMessage(message);
    expect(useDashboardStore.getState().view).toBe('get-started');
  });
});
