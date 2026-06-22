import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ContractTestPanel from '../components/ContractTestPanel';
import type { ContractTestReport } from '../lib/contractTest';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ContractTestPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

const mixedReport: ContractTestReport = {
  baseUrl: 'http://localhost:8080',
  totalOperations: 2,
  passed: 1,
  failed: 1,
  allPassed: false,
  results: [
    {
      operationId: 'getPet',
      method: 'GET',
      path: '/pet/{id}',
      statusCodeReceived: 200,
      passed: true,
      validationErrors: [],
    },
    {
      operationId: 'createPet',
      method: 'POST',
      path: '/pet',
      statusCodeReceived: 500,
      passed: false,
      validationErrors: ['response status 500 is not defined in the spec', 'body did not match schema'],
    },
  ],
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('ContractTestPanel', () => {
  it('renders title and form description', () => {
    renderPanel();
    expect(screen.getByText('Contract Test')).toBeInTheDocument();
    expect(screen.getByText(/Run an OpenAPI spec's operations against a live service/)).toBeInTheDocument();
  });

  it('disables Run until both a spec and a base URL are supplied', async () => {
    const user = userEvent.setup();
    renderPanel();

    const runButton = screen.getByRole('button', { name: /Run contract test/i });
    expect(runButton).toBeDisabled();

    await user.type(screen.getByRole('textbox', { name: /OpenAPI spec/i }), 'https://example.com/openapi.json');
    expect(runButton).toBeDisabled(); // still missing base URL

    await user.type(screen.getByRole('textbox', { name: /Target base URL/i }), 'http://localhost:8080');
    expect(runButton).not.toBeDisabled();
  });

  it('renders a pass/fail-per-operation table from a mocked report', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => mixedReport,
    }));
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderPanel();

    await user.type(screen.getByRole('textbox', { name: /OpenAPI spec/i }), 'https://example.com/openapi.json');
    await user.type(screen.getByRole('textbox', { name: /Target base URL/i }), 'http://localhost:8080');
    await user.click(screen.getByRole('button', { name: /Run contract test/i }));

    await waitFor(() => {
      expect(screen.getByText('getPet')).toBeInTheDocument();
    });

    // summary chips (the failed count appears both in the heading chip and the
    // report summary row)
    expect(screen.getAllByText('1 failed').length).toBeGreaterThan(0);
    expect(screen.getByText('1 passed')).toBeInTheDocument();
    expect(screen.getByText('2 operations')).toBeInTheDocument();

    // per-operation rows: one PASS, one FAIL
    expect(screen.getByText('PASS')).toBeInTheDocument();
    expect(screen.getByText('FAIL')).toBeInTheDocument();
    expect(screen.getByText('createPet')).toBeInTheDocument();
    expect(screen.getByText('/pet/{id}')).toBeInTheDocument();
    expect(screen.getByText('500')).toBeInTheDocument();

    // validation errors for the failing operation render
    expect(screen.getByText('response status 500 is not defined in the spec')).toBeInTheDocument();
    expect(screen.getByText('body did not match schema')).toBeInTheDocument();

    // it issued a PUT to the contractTest endpoint with the spec + baseUrl
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toContain('/mockserver/contractTest');
    expect(init.method).toBe('PUT');
    const sent = JSON.parse(init.body as string) as { spec: string; baseUrl: string };
    expect(sent.spec).toBe('https://example.com/openapi.json');
    expect(sent.baseUrl).toBe('http://localhost:8080');
  });

  it('shows an "All passed" chip when every operation passes', async () => {
    const allPass: ContractTestReport = {
      baseUrl: 'http://localhost:8080',
      totalOperations: 1,
      passed: 1,
      failed: 0,
      allPassed: true,
      results: [
        { operationId: 'getPet', method: 'GET', path: '/pet/{id}', statusCodeReceived: 200, passed: true, validationErrors: [] },
      ],
    };
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, status: 200, json: async () => allPass })));

    const user = userEvent.setup();
    renderPanel();

    await user.type(screen.getByRole('textbox', { name: /OpenAPI spec/i }), 'inline');
    await user.type(screen.getByRole('textbox', { name: /Target base URL/i }), 'http://localhost:8080');
    await user.click(screen.getByRole('button', { name: /Run contract test/i }));

    await waitFor(() => {
      expect(screen.getByText('All passed')).toBeInTheDocument();
    });
    expect(screen.getByText('PASS')).toBeInTheDocument();
  });

  it('surfaces a humanised error when the run fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
        json: async () => ({ error: 'invalid baseUrl: missing host' }),
      })),
    );

    const user = userEvent.setup();
    renderPanel();

    await user.type(screen.getByRole('textbox', { name: /OpenAPI spec/i }), 'inline');
    await user.type(screen.getByRole('textbox', { name: /Target base URL/i }), 'http://localhost:8080');
    await user.click(screen.getByRole('button', { name: /Run contract test/i }));

    // The lib surfaces the server's { error } envelope text, which humanizeError
    // passes through as the user-facing message.
    await waitFor(() => {
      expect(screen.getByText(/invalid baseUrl: missing host/)).toBeInTheDocument();
    });
  });
});
