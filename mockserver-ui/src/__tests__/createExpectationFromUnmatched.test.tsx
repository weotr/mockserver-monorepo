import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import { useDashboardStore } from '../store';
import DebugMismatchDialog from '../components/DebugMismatchDialog';
import type { DebugMismatchResult } from '../types';

const connectionParams = { host: 'localhost', port: '1080', secure: false };

function renderDialog() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <DebugMismatchDialog connectionParams={connectionParams} />
    </ThemeProvider>,
  );
}

const sampleResult: DebugMismatchResult = {
  correlationId: 'abc-123',
  timestamp: '2025-05-10T12:00:00Z',
  totalExpectations: 2,
  evaluatedExpectations: 2,
  closestMatch: { expectationId: 'exp-1', matchedFields: 10, totalFields: 12 },
  results: [
    {
      expectationId: 'exp-1',
      expectationMethod: 'GET',
      expectationPath: '/api/users',
      matches: false,
      matchedFieldCount: 10,
      totalFieldCount: 12,
      differences: {
        path: ['expected /api/users but was /api/items'],
      },
    },
  ],
};

const unmatchedRequest: Record<string, unknown> = {
  method: 'POST',
  path: '/api/items',
  headers: [
    { name: 'Content-Type', values: ['application/json'] },
  ],
  body: { type: 'STRING', string: '{"name":"widget"}' },
};

beforeEach(() => {
  vi.restoreAllMocks();
  useDashboardStore.setState({
    debugMismatchOpen: false,
    debugMismatchResult: null,
    debugMismatchLoading: false,
    debugMismatchError: null,
  });
});

describe('Create Expectation from Unmatched Request', () => {
  it('does not show Create Expectation button when no unmatched request is attached', () => {
    useDashboardStore.setState({
      debugMismatchOpen: true,
      debugMismatchResult: sampleResult,
    });
    renderDialog();
    expect(screen.queryByRole('button', { name: /Create Expectation/i })).not.toBeInTheDocument();
  });

  it('shows Create Expectation button when unmatchedRequest is present', () => {
    useDashboardStore.setState({
      debugMismatchOpen: true,
      debugMismatchResult: {
        ...sampleResult,
        unmatchedRequest,
      },
    });
    renderDialog();
    expect(screen.getByRole('button', { name: /Create Expectation/i })).toBeInTheDocument();
  });

  it('opens CaptureAsMockDialog prefilled with the unmatched request on click', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      debugMismatchOpen: true,
      debugMismatchResult: {
        ...sampleResult,
        unmatchedRequest,
      },
    });
    renderDialog();

    await user.click(screen.getByRole('button', { name: /Create Expectation/i }));

    // The CaptureAsMockDialog should now be open
    expect(screen.getByText('Capture as Mock')).toBeInTheDocument();

    // The dialog should be prefilled with the unmatched request data
    expect(screen.getByLabelText('Method')).toHaveValue('POST');
    expect(screen.getByLabelText('Path')).toHaveValue('/api/items');
    // Status code defaults to 200 since the unmatched request has no response
    expect(screen.getByLabelText('Status Code')).toHaveValue(200);
  });

  it('prefills request body from the unmatched request', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      debugMismatchOpen: true,
      debugMismatchResult: {
        ...sampleResult,
        unmatchedRequest,
      },
    });
    renderDialog();

    await user.click(screen.getByRole('button', { name: /Create Expectation/i }));

    // In moderate mode (default), body should be visible
    expect(screen.getByLabelText('Request Body')).toHaveValue('{"name":"widget"}');
  });

  it('shows matcher precision toggle in the capture dialog', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      debugMismatchOpen: true,
      debugMismatchResult: {
        ...sampleResult,
        unmatchedRequest,
      },
    });
    renderDialog();

    await user.click(screen.getByRole('button', { name: /Create Expectation/i }));

    expect(screen.getByText('Matcher Precision')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Exact precision' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Moderate precision' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Loose precision' })).toBeInTheDocument();
  });

  it('closes the capture dialog without closing the mismatch dialog', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      debugMismatchOpen: true,
      debugMismatchResult: {
        ...sampleResult,
        unmatchedRequest,
      },
    });
    renderDialog();

    await user.click(screen.getByRole('button', { name: /Create Expectation/i }));
    expect(screen.getByText('Capture as Mock')).toBeInTheDocument();

    // Click Cancel on the capture dialog
    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    // Capture dialog should be closed (wait for MUI transition) but mismatch dialog stays open
    await waitFor(() => {
      expect(screen.queryByText('Capture as Mock')).not.toBeInTheDocument();
    });
    expect(screen.getByText("Why Didn't This Match?")).toBeInTheDocument();
  });

  it('handles unmatched request with minimal fields', async () => {
    const user = userEvent.setup();
    const minimalRequest: Record<string, unknown> = {
      path: '/health',
    };
    useDashboardStore.setState({
      debugMismatchOpen: true,
      debugMismatchResult: {
        ...sampleResult,
        unmatchedRequest: minimalRequest,
      },
    });
    renderDialog();

    await user.click(screen.getByRole('button', { name: /Create Expectation/i }));

    expect(screen.getByLabelText('Method')).toHaveValue('GET');
    expect(screen.getByLabelText('Path')).toHaveValue('/health');
  });

  it('generates valid JSON when switching to Copy as JSON tab', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      debugMismatchOpen: true,
      debugMismatchResult: {
        ...sampleResult,
        unmatchedRequest,
      },
    });
    renderDialog();

    await user.click(screen.getByRole('button', { name: /Create Expectation/i }));

    // Switch to JSON tab inside the capture dialog
    await user.click(screen.getByRole('tab', { name: 'Copy as JSON' }));

    // The JSON output should contain httpRequest and httpResponse
    const pre = screen.getByText(/httpRequest/);
    const json = JSON.parse(pre.textContent!);
    expect(json).toHaveProperty('httpRequest');
    expect(json).toHaveProperty('httpResponse');
    expect(json.httpRequest.method).toBe('POST');
    expect(json.httpRequest.path).toBe('/api/items');
  });

  it('registers the expectation via PUT /mockserver/expectation', async () => {
    const user = userEvent.setup();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      text: () => Promise.resolve(''),
    }));

    useDashboardStore.setState({
      debugMismatchOpen: true,
      debugMismatchResult: {
        ...sampleResult,
        unmatchedRequest,
      },
    });
    renderDialog();

    await user.click(screen.getByRole('button', { name: /Create Expectation/i }));
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(fetch).toHaveBeenCalledTimes(1);
    const call = (fetch as ReturnType<typeof vi.fn>).mock.calls[0]!;
    expect(call[0]).toBe('http://localhost:1080/mockserver/expectation');
    expect(call[1].method).toBe('PUT');

    const body = JSON.parse(call[1].body);
    expect(body.httpRequest.method).toBe('POST');
    expect(body.httpRequest.path).toBe('/api/items');
  });

  it('does not auto-open capture dialog when re-opening mismatch dialog after a prior capture', async () => {
    const user = userEvent.setup();
    const resultWithRequest = {
      ...sampleResult,
      unmatchedRequest,
    };

    // Step 1: open mismatch dialog and click Create Expectation (sets captureOpen=true)
    useDashboardStore.setState({
      debugMismatchOpen: true,
      debugMismatchResult: resultWithRequest,
    });
    renderDialog();

    await user.click(screen.getByRole('button', { name: /Create Expectation/i }));
    expect(screen.getByText('Capture as Mock')).toBeInTheDocument();

    // Step 2: close the mismatch dialog while captureOpen is still true.
    // Use fireEvent (not userEvent) to bypass MUI backdrop pointer-events blocking.
    // The X icon button in the mismatch title bar calls the component's close handler.
    const closeIcons = screen.getAllByTestId('CloseIcon');
    expect(closeIcons.length).toBeGreaterThan(0);
    const mismatchCloseBtn = closeIcons[0]!.closest('button')!;
    fireEvent.click(mismatchCloseBtn);

    await waitFor(() => {
      expect(useDashboardStore.getState().debugMismatchOpen).toBe(false);
    });

    // Step 3: re-open the mismatch dialog with a different request
    useDashboardStore.setState({
      debugMismatchOpen: true,
      debugMismatchResult: {
        ...sampleResult,
        unmatchedRequest: { method: 'DELETE', path: '/api/other' },
      },
    });

    // The mismatch dialog should be open but capture dialog should NOT auto-open
    await waitFor(() => {
      expect(screen.getByText("Why Didn't This Match?")).toBeInTheDocument();
    });
    expect(screen.getByRole('button', { name: /Create Expectation/i })).toBeInTheDocument();
    expect(screen.queryByText('Capture as Mock')).not.toBeInTheDocument();
  });
});
