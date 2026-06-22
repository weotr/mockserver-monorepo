import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import DiffRequestsDialog from '../components/DiffRequestsDialog';

const connectionParams = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function renderDialog() {
  render(
    <ThemeProvider theme={buildTheme('dark')}>
      <DiffRequestsDialog open onClose={vi.fn()} connectionParams={connectionParams} />
    </ThemeProvider>,
  );
}

// JSON contains `{` which userEvent.type treats as a special key sequence, so set the textareas
// directly with fireEvent.change.
describe('DiffRequestsDialog', () => {
  it('rejects invalid JSON without calling the server', async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    fireEvent.change(screen.getByLabelText(/Expected request/), { target: { value: 'not json' } });
    fireEvent.change(screen.getByLabelText(/Actual request/), { target: { value: '{}' } });
    await user.click(screen.getByRole('button', { name: 'Compare' }));

    expect(await screen.findByText(/Expected.*request is not valid JSON/)).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('PUTs both requests to /diff and renders the diff result', async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        diffCount: 1,
        identical: false,
        diffs: [{ field: 'method', expectedValue: 'GET', actualValue: 'POST', diffType: 'CHANGED' }],
      }),
    });
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    fireEvent.change(screen.getByLabelText(/Expected request/), { target: { value: '{"method":"GET"}' } });
    fireEvent.change(screen.getByLabelText(/Actual request/), { target: { value: '{"method":"POST"}' } });
    await user.click(screen.getByRole('button', { name: 'Compare' }));

    expect(await screen.findByText('Request Diff')).toBeInTheDocument();
    expect(screen.getByText('1 differences')).toBeInTheDocument();
    expect(screen.getByText('method')).toBeInTheDocument();

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/diff');
    expect((init as RequestInit).method).toBe('PUT');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({
      expected: { method: 'GET' },
      actual: { method: 'POST' },
    });
  });

  it('auto-runs the diff on open when both requests are pre-populated (Traffic Compare flow)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ diffCount: 0, identical: true, diffs: [] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    render(
      <ThemeProvider theme={buildTheme('dark')}>
        <DiffRequestsDialog
          open
          onClose={vi.fn()}
          connectionParams={connectionParams}
          initialExpected={'{"method":"GET"}'}
          initialActual={'{"method":"GET"}'}
        />
      </ThemeProvider>,
    );

    // No "Compare" click — the result appears on its own.
    expect(await screen.findByText('Request Diff')).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
