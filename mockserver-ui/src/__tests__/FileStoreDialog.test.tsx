import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import FileStoreDialog from '../components/FileStoreDialog';

const connectionParams = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function renderDialog() {
  render(
    <ThemeProvider theme={buildTheme('dark')}>
      <FileStoreDialog open onClose={vi.fn()} connectionParams={connectionParams} />
    </ThemeProvider>,
  );
}

describe('FileStoreDialog', () => {
  it('lists files from the server on open', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ['hello.txt', 'logo.png'] }));
    renderDialog();
    expect(await screen.findByText('hello.txt')).toBeInTheDocument();
    expect(screen.getByText('logo.png')).toBeInTheDocument();
  });

  it('stores a file via PUT /files/store and refreshes', async () => {
    // /files/list returns [] initially, then the stored file after refresh; /files/store returns metadata.
    const stored: string[] = [];
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith('/files/store')) {
        const body = JSON.parse((init?.body as string) ?? '{}') as { name: string };
        stored.push(body.name);
        return { ok: true, json: async () => ({ name: body.name, size: 5 }) };
      }
      // /files/list
      return { ok: true, json: async () => [...stored] };
    });
    vi.stubGlobal('fetch', fetchMock);
    renderDialog();

    const user = userEvent.setup();
    await user.type(screen.getByLabelText(/File name/), 'note.txt');
    await user.type(screen.getByLabelText(/Content/), 'hi');
    await user.click(screen.getByRole('button', { name: /Store File/ }));

    expect(await screen.findByText(/Stored "note.txt"/)).toBeInTheDocument();
    const storeCall = fetchMock.mock.calls.find((c) => (c[0] as string).endsWith('/files/store'))!;
    expect((storeCall[1] as RequestInit).method).toBe('PUT');
    expect(JSON.parse((storeCall[1] as RequestInit).body as string)).toMatchObject({ name: 'note.txt', content: 'hi' });
    // the newly stored file appears in the refreshed list
    expect(await screen.findByText('note.txt')).toBeInTheDocument();
  });

  it('surfaces a server error from the list call', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500, statusText: 'Server Error', text: async () => 'boom' }));
    renderDialog();
    // The list call now throws the standard `MockServer returned 500: boom` shape, which the dialog
    // surfaces through humanizeError -> a friendly 5xx message.
    expect(await screen.findByText('The MockServer encountered an internal error.')).toBeInTheDocument();
  });
});
