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
  it('shows human-readable field labels and helper text', () => {
    renderDialog();

    // Human-readable labels (not raw API identifiers).
    expect(screen.getByLabelText(/Resource path/)).toBeInTheDocument();
    expect(screen.getByLabelText(/ID field name/)).toBeInTheDocument();
    expect(screen.getByLabelText(/ID strategy/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Seed data/)).toBeInTheDocument();

    // The raw API identifiers are no longer used as labels.
    expect(screen.queryByLabelText('basePath')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('idField')).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/initialData/)).not.toBeInTheDocument();

    // Concise helper text accompanies the fields.
    expect(screen.getByText(/The base path the CRUD endpoints are mounted under\./)).toBeInTheDocument();
    expect(screen.getByText(/pre-populate the resource/)).toBeInTheDocument();
  });

  it('still sends the unchanged API field keys in the request body', async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      json: async () => ({ basePath: '/api/users', idField: 'id', idStrategy: 'UUID', itemCount: 0 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    fireEvent.change(screen.getByLabelText(/Resource path/), { target: { value: '/api/users' } });
    fireEvent.change(screen.getByLabelText(/ID field name/), { target: { value: 'uuid' } });
    await user.click(screen.getByRole('button', { name: 'Register' }));

    await screen.findByText(/Registered CRUD resource at/);
    const [, init] = fetchMock.mock.calls[0]!;
    // Underlying field keys must remain the raw API identifiers.
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({
      basePath: '/api/users',
      idField: 'uuid',
    });
  });

  it('rejects invalid initialData JSON without calling the server', async () => {
    const user = userEvent.setup();
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    fireEvent.change(screen.getByLabelText(/Resource path/), { target: { value: '/api/users' } });
    fireEvent.change(screen.getByLabelText(/Seed data/), { target: { value: 'not json' } });
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

    fireEvent.change(screen.getByLabelText(/Resource path/), { target: { value: '/api/users' } });
    fireEvent.change(screen.getByLabelText(/Seed data/), { target: { value: '[{"name":"a"},{"name":"b"}]' } });
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
