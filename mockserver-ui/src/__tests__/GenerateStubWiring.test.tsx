import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import LogEntry from '../components/LogEntry';
import GenerateStubDialog from '../components/GenerateStubDialog';
import { GenerateStubContext } from '../hooks/GenerateStubContext';
import { DebugMismatchContext } from '../hooks/DebugMismatchContext';
import { useDashboardStore } from '../store';
import type { LogEntryValue } from '../types';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** An unmatched log entry (EXPECTATION_NOT_MATCHED) with an extractable request. */
const unmatchedEntry: LogEntryValue = {
  description: '10:00:00 EXPECTATION_NOT_MATCHED',
  messageParts: [
    { key: 'msg_0', value: 'no expectation for ' },
    {
      key: 'msg_1',
      value: { method: 'GET', path: '/api/missing' },
      json: true,
      argument: true,
    },
    { key: 'msg_2', value: ' closest match:' },
    {
      key: 'msg_3',
      value: { method: 'GET', path: '/api/found' },
      json: true,
      argument: true,
    },
  ],
};

/** A matched log entry (should not show the generate stub button). */
const matchedEntry: LogEntryValue = {
  description: '10:00:00 EXPECTATION_MATCHED',
  messageParts: [
    { key: 'msg_0', value: 'matched expectation' },
  ],
};

const connectionParams = { host: '127.0.0.1', port: '1080', secure: false };

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

beforeEach(() => {
  useDashboardStore.setState({
    generateStubOpen: false,
    generateStubLoading: false,
    generateStubSuggestions: [],
    generateStubConfidence: 0,
    generateStubError: null,
  });
  vi.restoreAllMocks();
});

describe('Generate Stub button in LogEntry', () => {
  it('shows the generate stub button for unmatched entries when context is provided', () => {
    const mockGenerateStub = vi.fn().mockResolvedValue(undefined);

    render(
      <DebugMismatchContext.Provider value={null}>
        <GenerateStubContext.Provider value={mockGenerateStub}>
          <LogEntry entry={unmatchedEntry} collapsible />
        </GenerateStubContext.Provider>
      </DebugMismatchContext.Provider>,
    );

    expect(screen.getByTestId('AutoFixHighIcon')).toBeInTheDocument();
  });

  it('does not show the generate stub button for matched entries', () => {
    const mockGenerateStub = vi.fn().mockResolvedValue(undefined);

    render(
      <DebugMismatchContext.Provider value={null}>
        <GenerateStubContext.Provider value={mockGenerateStub}>
          <LogEntry entry={matchedEntry} collapsible />
        </GenerateStubContext.Provider>
      </DebugMismatchContext.Provider>,
    );

    expect(screen.queryByTestId('AutoFixHighIcon')).not.toBeInTheDocument();
  });

  it('does not show the generate stub button when no context is provided', () => {
    render(
      <DebugMismatchContext.Provider value={null}>
        <GenerateStubContext.Provider value={null}>
          <LogEntry entry={unmatchedEntry} collapsible />
        </GenerateStubContext.Provider>
      </DebugMismatchContext.Provider>,
    );

    expect(screen.queryByTestId('AutoFixHighIcon')).not.toBeInTheDocument();
  });

  it('calls the generateStub callback with the extracted request when clicked', async () => {
    const user = userEvent.setup();
    const mockGenerateStub = vi.fn().mockResolvedValue(undefined);

    render(
      <DebugMismatchContext.Provider value={null}>
        <GenerateStubContext.Provider value={mockGenerateStub}>
          <LogEntry entry={unmatchedEntry} collapsible />
        </GenerateStubContext.Provider>
      </DebugMismatchContext.Provider>,
    );

    const stubButton = screen.getByTestId('AutoFixHighIcon').closest('button')!;
    await user.click(stubButton);

    expect(mockGenerateStub).toHaveBeenCalledOnce();
    // The request is extracted from the second JSON argument part (index 1)
    expect(mockGenerateStub).toHaveBeenCalledWith({ method: 'GET', path: '/api/found' });
  });
});

describe('Generate Stub end-to-end: fetch + dialog', () => {
  it('calls /generateExpectation and opens the dialog with the returned suggestion', async () => {
    const user = userEvent.setup();

    const mockSuggestion = {
      httpRequest: { method: 'GET', path: '/api/missing' },
      httpResponse: { statusCode: 200, body: 'OK' },
    };
    const mockResponse = {
      suggestions: [mockSuggestion],
      confidence: 0.85,
    };

    // Mock fetch to intercept the generateExpectation call
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: true,
      json: () => Promise.resolve(mockResponse),
      text: () => Promise.resolve(JSON.stringify(mockResponse)),
    } as Response);

    // Import and use the real useGenerateStub hook via a test wrapper
    const { useGenerateStub } = await import('../hooks/useGenerateStub');

    function TestHarness() {
      const { generateStub } = useGenerateStub(connectionParams);
      const generateStubOpen = useDashboardStore((s) => s.generateStubOpen);
      const generateStubSuggestions = useDashboardStore((s) => s.generateStubSuggestions);
      const generateStubConfidence = useDashboardStore((s) => s.generateStubConfidence);
      const closeGenerateStub = useDashboardStore((s) => s.closeGenerateStub);

      return (
        <DebugMismatchContext.Provider value={null}>
          <GenerateStubContext.Provider value={generateStub}>
            <LogEntry entry={unmatchedEntry} collapsible />
            <GenerateStubDialog
              open={generateStubOpen}
              onClose={closeGenerateStub}
              suggestions={generateStubSuggestions}
              confidence={generateStubConfidence}
              connectionParams={connectionParams}
            />
          </GenerateStubContext.Provider>
        </DebugMismatchContext.Provider>
      );
    }

    render(<TestHarness />);

    // Click the generate stub button
    const stubButton = screen.getByTestId('AutoFixHighIcon').closest('button')!;
    await user.click(stubButton);

    // Verify the fetch call was made to the correct endpoint
    await waitFor(() => {
      expect(fetchSpy).toHaveBeenCalledWith(
        'http://127.0.0.1:1080/mockserver/generateExpectation',
        expect.objectContaining({
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
        }),
      );
    });

    // Verify the dialog opens with the suggestion
    await waitFor(() => {
      expect(screen.getByText('Generated Expectation')).toBeInTheDocument();
    });

    // Verify confidence is displayed (85%)
    expect(screen.getByText('85% confidence')).toBeInTheDocument();

    // Verify the Register Now button is available
    expect(screen.getByRole('button', { name: 'Register Now' })).toBeInTheDocument();

    fetchSpy.mockRestore();
  });

  it('shows error in the store when the fetch fails', async () => {
    const user = userEvent.setup();

    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: false,
      json: () => Promise.resolve({ error: 'Server error' }),
      text: () => Promise.resolve('Server error'),
    } as Response);

    const { useGenerateStub } = await import('../hooks/useGenerateStub');

    function TestHarness() {
      const { generateStub } = useGenerateStub(connectionParams);
      const generateStubError = useDashboardStore((s) => s.generateStubError);

      return (
        <DebugMismatchContext.Provider value={null}>
          <GenerateStubContext.Provider value={generateStub}>
            <LogEntry entry={unmatchedEntry} collapsible />
            {generateStubError && <div data-testid="stub-error">{generateStubError}</div>}
          </GenerateStubContext.Provider>
        </DebugMismatchContext.Provider>
      );
    }

    render(<TestHarness />);

    const stubButton = screen.getByTestId('AutoFixHighIcon').closest('button')!;
    await user.click(stubButton);

    await waitFor(() => {
      expect(screen.getByTestId('stub-error')).toHaveTextContent('Server error');
    });

    fetchSpy.mockRestore();
  });
});
