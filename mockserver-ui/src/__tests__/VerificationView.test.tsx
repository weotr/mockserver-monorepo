import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import VerificationView from '../components/VerificationView';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderView() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <VerificationView connectionParams={params} />
    </ThemeProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('VerificationView', () => {
  it('renders in single-request mode by default', () => {
    renderView();
    expect(screen.getByText('Verification')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Single request', pressed: true })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify' })).toBeInTheDocument();
  });

  it('shows the times selector with at-least selected by default', () => {
    renderView();
    // The Select should show "at least"
    expect(screen.getByText('at least')).toBeInTheDocument();
  });

  it('switches to sequence mode and back', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));
    expect(screen.getByText(/requests must have been received in this order/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Verify sequence' })).toBeInTheDocument();
    // Sequence mode starts with 2 steps
    expect(screen.getByText('1.')).toBeInTheDocument();
    expect(screen.getByText('2.')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Single request' }));
    expect(screen.getByRole('button', { name: 'Verify' })).toBeInTheDocument();
  });

  it('adds a step in sequence mode', async () => {
    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));
    await user.click(screen.getByRole('button', { name: /Add step/i }));

    expect(screen.getByText('3.')).toBeInTheDocument();
  });

  it('shows success alert when verification passes (202)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        status: 202,
        text: async () => '',
      }),
    );

    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(screen.getByText(/Verified/)).toBeInTheDocument();
    });
  });

  it('shows failure alert when verification fails (406)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        status: 406,
        statusText: 'Not Acceptable',
        text: async () => 'Request not found exactly 1 times',
      }),
    );

    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(screen.getByText('Verification failed')).toBeInTheDocument();
    });
    expect(screen.getByText(/Request not found/)).toBeInTheDocument();
  });

  it('shows error alert on network failure', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new Error('network down')),
    );

    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Verify' }));

    await waitFor(() => {
      expect(screen.getByText('network down')).toBeInTheDocument();
    });
  });

  it('sends the correct body for sequence verification', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      status: 202,
      text: async () => '',
    });
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderView();

    await user.click(screen.getByRole('button', { name: 'Ordered sequence' }));
    await user.click(screen.getByRole('button', { name: 'Verify sequence' }));

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledOnce();
    });

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toContain('/mockserver/verifySequence');
    const body = JSON.parse(init.body as string);
    expect(body.httpRequests).toBeInstanceOf(Array);
    expect(body.httpRequests).toHaveLength(2);
  });
});
