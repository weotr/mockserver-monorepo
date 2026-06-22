import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import GrpcServicesPanel from '../components/GrpcServicesPanel';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <GrpcServicesPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

/** Route fetch by URL so the panel's parallel services + health calls resolve. */
function stubFetch(services: unknown, health: unknown) {
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
    if (url.endsWith('/grpc/services')) {
      return Promise.resolve({ ok: true, json: async () => services });
    }
    if (url.endsWith('/grpc/health')) {
      return Promise.resolve({ ok: true, json: async () => health });
    }
    return Promise.resolve({ ok: false, status: 404, text: async () => 'not found' });
  }));
}

afterEach(() => { vi.restoreAllMocks(); });

describe('GrpcServicesPanel', () => {
  it('shows the empty state when no services are loaded', async () => {
    stubFetch([], { _default: 'SERVING' });
    renderPanel();
    await waitFor(() => {
      expect(screen.getByText(/No gRPC services loaded/i)).toBeInTheDocument();
    });
  });

  it('renders services, methods, and per-service health', async () => {
    stubFetch(
      [
        {
          name: 'com.example.grpc.GreetingService',
          methods: [
            { name: 'Greeting', inputType: 'HelloRequest', outputType: 'HelloResponse', clientStreaming: false, serverStreaming: false },
            { name: 'Chat', inputType: 'HelloRequest', outputType: 'HelloResponse', clientStreaming: true, serverStreaming: true },
          ],
        },
      ],
      { _default: 'SERVING', 'com.example.grpc.GreetingService': 'NOT_SERVING' },
    );
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('com.example.grpc.GreetingService')).toBeInTheDocument();
    });
    expect(screen.getByText('Greeting')).toBeInTheDocument();
    expect(screen.getByText('Chat')).toBeInTheDocument();
    // streaming kind chips
    expect(screen.getByText('unary')).toBeInTheDocument();
    expect(screen.getByText('bidi stream')).toBeInTheDocument();
    // service health chip
    expect(screen.getByText('NOT_SERVING')).toBeInTheDocument();
    // overall server health chip
    expect(screen.getByText('server SERVING')).toBeInTheDocument();
  });

  it('shows a humanised error alert when the services fetch fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
      if (url.endsWith('/grpc/services')) {
        return Promise.resolve({ ok: false, status: 404, text: async () => 'not found' });
      }
      return Promise.resolve({ ok: true, json: async () => ({}) });
    }));
    renderPanel();
    await waitFor(() => {
      expect(screen.getByText(/isn’t available|not available/i)).toBeInTheDocument();
    });
  });
});
