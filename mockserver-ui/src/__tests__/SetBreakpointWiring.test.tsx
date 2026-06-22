import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import LogEntry from '../components/LogEntry';
import { SetBreakpointContext } from '../hooks/SetBreakpointContext';
import { useDashboardStore } from '../store';
import type { LogEntryValue } from '../types';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** A received-request log entry carrying a request (method + path) in a JSON part. */
const requestEntry: LogEntryValue = {
  description: '10:00:00 RECEIVED_REQUEST',
  messageParts: [
    { key: 'msg_0', value: 'received request:' },
    {
      key: 'msg_1',
      value: { method: 'POST', path: '/api/orders' },
      json: true,
      argument: true,
    },
  ],
};

/** A matched entry whose request is nested under httpRequest. */
const nestedRequestEntry: LogEntryValue = {
  description: '10:00:00 EXPECTATION_MATCHED',
  messageParts: [
    { key: 'msg_0', value: 'matched expectation' },
    {
      key: 'msg_1',
      value: { httpRequest: { method: 'GET', path: '/widgets' } },
      json: true,
      argument: true,
    },
  ],
};

/** A system message with no request JSON — no breakpoint can be derived. */
const noRequestEntry: LogEntryValue = {
  description: '10:00:00 SERVER_CONFIGURATION',
  messageParts: [
    { key: 'msg_0', value: 'configuration updated' },
  ],
};

beforeEach(() => {
  useDashboardStore.setState({ pendingBreakpointPrefill: null, view: 'dashboard' });
  vi.restoreAllMocks();
});

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('Set breakpoint button in LogEntry', () => {
  it('shows the button for a row carrying a request when the context is provided', () => {
    render(
      <SetBreakpointContext.Provider value={vi.fn()}>
        <LogEntry entry={requestEntry} collapsible />
      </SetBreakpointContext.Provider>,
    );
    expect(screen.getByTestId('PauseCircleIcon')).toBeInTheDocument();
  });

  it('does not show the button for rows with no extractable request', () => {
    render(
      <SetBreakpointContext.Provider value={vi.fn()}>
        <LogEntry entry={noRequestEntry} collapsible />
      </SetBreakpointContext.Provider>,
    );
    expect(screen.queryByTestId('PauseCircleIcon')).not.toBeInTheDocument();
  });

  it('does not show the button when no context is provided', () => {
    render(
      <SetBreakpointContext.Provider value={null}>
        <LogEntry entry={requestEntry} collapsible />
      </SetBreakpointContext.Provider>,
    );
    expect(screen.queryByTestId('PauseCircleIcon')).not.toBeInTheDocument();
  });

  it('pre-fills the breakpoint with the row method + path when clicked', async () => {
    const user = userEvent.setup();
    const setBreakpoint = vi.fn();

    render(
      <SetBreakpointContext.Provider value={setBreakpoint}>
        <LogEntry entry={requestEntry} collapsible />
      </SetBreakpointContext.Provider>,
    );

    const button = screen.getByTestId('PauseCircleIcon').closest('button')!;
    await user.click(button);

    expect(setBreakpoint).toHaveBeenCalledOnce();
    expect(setBreakpoint).toHaveBeenCalledWith({ method: 'POST', path: '/api/orders' });
  });

  it('reads the request nested under httpRequest', async () => {
    const user = userEvent.setup();
    const setBreakpoint = vi.fn();

    render(
      <SetBreakpointContext.Provider value={setBreakpoint}>
        <LogEntry entry={nestedRequestEntry} collapsible />
      </SetBreakpointContext.Provider>,
    );

    const button = screen.getByTestId('PauseCircleIcon').closest('button')!;
    await user.click(button);

    expect(setBreakpoint).toHaveBeenCalledWith({ method: 'GET', path: '/widgets' });
  });

  it('store action seeds the prefill and switches to the Breakpoints view', () => {
    useDashboardStore.getState().setBreakpointPrefill({ method: 'DELETE', path: '/cart' });
    const state = useDashboardStore.getState();
    expect(state.pendingBreakpointPrefill).toEqual({ method: 'DELETE', path: '/cart' });
    expect(state.view).toBe('breakpoints');
  });
});
