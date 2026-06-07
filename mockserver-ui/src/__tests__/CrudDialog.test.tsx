import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import CrudDialog from '../components/CrudDialog';

const connectionParams = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function renderDialog() {
  render(
    <ThemeProvider theme={buildTheme('dark')}>
      <CrudDialog open onClose={vi.fn()} connectionParams={connectionParams} />
    </ThemeProvider>,
  );
}

// JSON contains `{`/`[` which userEvent.type treats as special key sequences, so set those
// fields' values directly with fireEvent.change.
describe('CrudDialog', () => {
  it('rejects invalid initialData JSON without calling the server', async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    fireEvent.change(screen.getByLabelText(/basePath/), { target: { value: '/api/users' } });
    fireEvent.change(screen.getByLabelText(/initialData/), { target: { value: 'not json' } });
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(screen.getByText('initialData is not valid JSON.')).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('PUTs the config and shows a success summary', async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => ({ basePath: '/api/users', idField: 'id', idStrategy: 'AUTO_INCREMENT', itemCount: 2 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    fireEvent.change(screen.getByLabelText(/basePath/), { target: { value: '/api/users' } });
    fireEvent.change(screen.getByLabelText(/initialData/), { target: { value: '[{"name":"a"},{"name":"b"}]' } });
    await user.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText(/Registered CRUD resource at/)).toBeInTheDocument();
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/crud');
    expect((init as RequestInit).method).toBe('PUT');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({
      basePath: '/api/users',
      initialData: [{ name: 'a' }, { name: 'b' }],
    });
  });
});
