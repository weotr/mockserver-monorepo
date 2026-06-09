import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import AppBar from '../components/AppBar';
import { useDashboardStore } from '../store';
import * as http3StatusModule from '../lib/http3Status';

function renderAppBar(overrides = {}) {
  const defaults = {
    onClearServer: vi.fn().mockResolvedValue(undefined),
    onClearLogs: vi.fn().mockResolvedValue(undefined),
    onClearExpectations: vi.fn().mockResolvedValue(undefined),
  };
  const props = { ...defaults, ...overrides };
  return {
    ...render(
      <ThemeProvider theme={buildTheme('dark')}>
        <AppBar {...props} />
      </ThemeProvider>,
    ),
    props,
  };
}

describe('AppBar', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      connectionStatus: 'connected',
      themeMode: 'dark',
      autoScroll: true,
    });
  });

  it('displays the MockServer title', () => {
    renderAppBar();
    expect(screen.getByText('MockServer')).toBeInTheDocument();
  });

  it('shows connection status chip', () => {
    renderAppBar();
    expect(screen.getByText('connected')).toBeInTheDocument();
  });

  it('shows different connection statuses', () => {
    useDashboardStore.setState({ connectionStatus: 'error' });
    renderAppBar();
    expect(screen.getByText('error')).toBeInTheDocument();
  });

  it('toggles theme when theme button is clicked', async () => {
    const user = userEvent.setup();
    renderAppBar();

    const themeButtons = screen.getAllByRole('button');
    const themeButton = themeButtons.find((b) => b.getAttribute('aria-label')?.includes('light') || b.querySelector('[data-testid="LightModeIcon"]'));

    if (themeButton) {
      await user.click(themeButton);
      expect(useDashboardStore.getState().themeMode).toBe('light');
    }
  });

  it('opens clear menu and calls clear server on reset after confirmation', async () => {
    const user = userEvent.setup();
    const { props } = renderAppBar();

    const clearButton = screen.getAllByRole('button').find(
      (b) => b.querySelector('[data-testid="DeleteSweepIcon"]'),
    );
    expect(clearButton).toBeDefined();

    await user.click(clearButton!);
    expect(screen.getByText('Reset server (all)')).toBeInTheDocument();

    // Reset is destructive — it opens a confirmation dialog rather than firing immediately.
    await user.click(screen.getByText('Reset server (all)'));
    expect(props.onClearServer).not.toHaveBeenCalled();
    expect(screen.getByText('Reset the entire server?')).toBeInTheDocument();

    // Confirm in the dialog.
    await user.click(screen.getByRole('button', { name: 'Reset server' }));
    expect(props.onClearServer).toHaveBeenCalledOnce();
  });

  it('shows the Mocks tab label (not Composer)', () => {
    renderAppBar();
    expect(screen.getByText('Mocks')).toBeInTheDocument();
    expect(screen.queryByText('Composer')).not.toBeInTheDocument();
  });

  it('does not show a standalone MCP tab', () => {
    renderAppBar();
    // The MCP tab was removed and folded into the Mocks page
    const mcpButton = screen.queryByRole('button', { name: /MCP tools view/i });
    expect(mcpButton).not.toBeInTheDocument();
  });

  it('calls onClearLogs when clear server logs is clicked', async () => {
    const user = userEvent.setup();
    const { props } = renderAppBar();

    const clearButton = screen.getAllByRole('button').find(
      (b) => b.querySelector('[data-testid="DeleteSweepIcon"]'),
    );
    await user.click(clearButton!);
    await user.click(screen.getByText('Clear server logs'));

    expect(props.onClearLogs).toHaveBeenCalledOnce();
    expect(props.onClearServer).not.toHaveBeenCalled();
  });

  it('shows HTTP/3 status chip when H3 is enabled', async () => {
    vi.spyOn(http3StatusModule, 'fetchHttp3Status').mockResolvedValue({
      enabled: true,
      port: 8443,
      activeConnections: 2,
    });

    renderAppBar();

    await waitFor(() => {
      expect(screen.getByText('H3 :8443 (2)')).toBeInTheDocument();
    });
  });

  it('does not show HTTP/3 chip when H3 is disabled', async () => {
    const spy = vi.spyOn(http3StatusModule, 'fetchHttp3Status').mockResolvedValue({
      enabled: false,
      port: -1,
      activeConnections: 0,
    });

    renderAppBar();

    // Wait for the H3 status effect to complete before asserting absence.
    // Using waitFor on the spy ensures the async effect has settled.
    await waitFor(() => expect(spy).toHaveBeenCalled());
    expect(screen.queryByText(/^H3 :/)).not.toBeInTheDocument();
  });

  it('does not show HTTP/3 chip when endpoint is unavailable', async () => {
    const spy = vi.spyOn(http3StatusModule, 'fetchHttp3Status').mockRejectedValue(
      new Error('Not Found'),
    );

    renderAppBar();

    // Wait for the H3 status effect to complete before asserting absence.
    await waitFor(() => expect(spy).toHaveBeenCalled());
    expect(screen.queryByText(/^H3 :/)).not.toBeInTheDocument();
  });
});
