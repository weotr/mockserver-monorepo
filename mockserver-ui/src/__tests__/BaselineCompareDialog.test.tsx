import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import BaselineCompareDialog from '../components/BaselineCompareDialog';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderDialog(open = true) {
  const onClose = vi.fn();
  render(
    <ThemeProvider theme={buildTheme('dark')}>
      <BaselineCompareDialog open={open} onClose={onClose} connectionParams={params} />
    </ThemeProvider>,
  );
  return { onClose };
}

afterEach(() => { vi.restoreAllMocks(); });

describe('BaselineCompareDialog', () => {
  it('runs the comparison and shows a drift summary', async () => {
    const report = {
      added: [{ key: 'GET /api/users' }],
      removed: [{ key: 'GET /hello' }],
      changed: [],
      hasDrift: true,
    };
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => report });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderDialog();

    fireEvent.change(screen.getByLabelText(/Baseline expectations/i), {
      target: { value: '[{"httpRequest":{"path":"/hello"}}]' },
    });
    await user.click(screen.getByRole('button', { name: /^Compare$/ }));

    await waitFor(() => {
      expect(screen.getByText('drift detected')).toBeInTheDocument();
    });
    expect(screen.getByText('1 added')).toBeInTheDocument();
    expect(screen.getByText('1 removed')).toBeInTheDocument();

    // sent to the right endpoint
    expect(fetchMock.mock.calls[0]![0]).toBe('http://127.0.0.1:1080/mockserver/baseline/compare');
  });

  it('rejects invalid JSON before calling the server', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderDialog();

    fireEvent.change(screen.getByLabelText(/Baseline expectations/i), {
      target: { value: 'not json' },
    });
    await user.click(screen.getByRole('button', { name: /^Compare$/ }));

    await waitFor(() => {
      expect(screen.getByText(/baseline must be valid JSON/i)).toBeInTheDocument();
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('shows a humanised error when the server rejects the request', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      text: async () => 'baseline compare request body must contain a "baseline" array of expectations',
    }));

    const user = userEvent.setup();
    renderDialog();

    fireEvent.change(screen.getByLabelText(/Baseline expectations/i), {
      target: { value: '[]' },
    });
    await user.click(screen.getByRole('button', { name: /^Compare$/ }));

    await waitFor(() => {
      expect(screen.getByText(/rejected as invalid/i)).toBeInTheDocument();
    });
  });
});
