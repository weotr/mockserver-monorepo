import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import SloPanel from '../components/SloPanel';
import type { SloVerdict } from '../lib/slo';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <SloPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

/** Stub fetch to return a verdict (200 for PASS/INCONCLUSIVE, 406 for FAIL). */
function stubVerify(verdict: SloVerdict, status = 200) {
  const fetchMock = vi.fn(async () => ({
    ok: status >= 200 && status < 300,
    status,
    statusText: status === 406 ? 'Not Acceptable' : 'OK',
    json: async () => verdict,
  }));
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('SloPanel', () => {
  it('renders title, description and the verify control', () => {
    renderPanel();
    expect(screen.getByText('SLO Verification')).toBeInTheDocument();
    expect(screen.getByText(/Assert service-level objectives/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Verify SLO/i })).toBeInTheDocument();
  });

  it('seeds default objectives the user can author', () => {
    renderPanel();
    // Two default objective rows (latency p95 + error rate).
    expect(screen.getByLabelText('Objective 1 indicator')).toBeInTheDocument();
    expect(screen.getByLabelText('Objective 2 indicator')).toBeInTheDocument();
  });

  it('PUTs the authored criteria to /mockserver/verifySLO on submit', async () => {
    const user = userEvent.setup();
    const fetchMock = stubVerify({
      name: 'checkout-slo',
      result: 'PASS',
      windowFromEpochMillis: 0,
      windowToEpochMillis: 1,
      sampleCount: 10,
      objectiveResults: [
        { sli: 'LATENCY_P95', comparator: 'LESS_THAN', threshold: 250, observedValue: 120, result: 'PASS' },
      ],
    });

    renderPanel();
    await user.click(screen.getByRole('button', { name: /Verify SLO/i }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());

    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('http://127.0.0.1:1080/mockserver/verifySLO');
    expect(init.method).toBe('PUT');
    const body = JSON.parse(init.body as string);
    expect(body.name).toBe('checkout-slo');
    expect(body.window).toEqual({ type: 'LOOKBACK', lookbackMillis: 60000 });
    expect(body.minimumSampleCount).toBe(1);
    // default objectives are authored with FORWARD scope and parsed numeric thresholds
    expect(body.objectives).toEqual([
      { sli: 'LATENCY_P95', comparator: 'LESS_THAN', threshold: 250, scope: 'FORWARD' },
      { sli: 'ERROR_RATE', comparator: 'LESS_THAN_OR_EQUAL', threshold: 0.01, scope: 'FORWARD' },
    ]);
  });

  it('renders a PASS verdict with the per-objective observed value', async () => {
    const user = userEvent.setup();
    stubVerify({
      name: 'checkout-slo',
      result: 'PASS',
      windowFromEpochMillis: 0,
      windowToEpochMillis: 1,
      sampleCount: 42,
      objectiveResults: [
        { sli: 'LATENCY_P95', comparator: 'LESS_THAN', threshold: 250, observedValue: 123.4, result: 'PASS' },
      ],
    }, 200);

    renderPanel();
    await user.click(screen.getByRole('button', { name: /Verify SLO/i }));

    await waitFor(() => {
      // overall verdict chip
      expect(screen.getByText('checkout-slo: PASS')).toBeInTheDocument();
    });
    expect(screen.getByText(/Evaluated 42 samples/)).toBeInTheDocument();
    expect(screen.getByText('123.4')).toBeInTheDocument();
  });

  it('renders a FAIL verdict (delivered as a 406) with the breaching observed value', async () => {
    const user = userEvent.setup();
    stubVerify({
      name: 'checkout-slo',
      result: 'FAIL',
      windowFromEpochMillis: 0,
      windowToEpochMillis: 1,
      sampleCount: 7,
      objectiveResults: [
        { sli: 'LATENCY_P95', comparator: 'LESS_THAN', threshold: 250, observedValue: 310, result: 'FAIL' },
      ],
    }, 406);

    renderPanel();
    await user.click(screen.getByRole('button', { name: /Verify SLO/i }));

    await waitFor(() => {
      expect(screen.getByText('checkout-slo: FAIL')).toBeInTheDocument();
    });
    expect(screen.getByText('310.0')).toBeInTheDocument();
    // The per-objective FAIL chip is present too.
    expect(screen.getAllByText('FAIL').length).toBeGreaterThan(0);
  });

  it('surfaces a humanised error when the server rejects the criteria (400)', async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 400,
        statusText: 'Bad Request',
        json: async () => ({ error: 'SLO tracking not enabled (set sloTrackingEnabled=true)' }),
      })),
    );

    renderPanel();
    await user.click(screen.getByRole('button', { name: /Verify SLO/i }));

    await waitFor(() => {
      expect(screen.getByText('Could not verify SLO')).toBeInTheDocument();
    });
    expect(screen.getByText(/SLO tracking not enabled/)).toBeInTheDocument();
  });

  it('disables Verify and never submits when the lookback is zero/empty', async () => {
    const user = userEvent.setup();
    const fetchMock = stubVerify({
      // verdict shape is irrelevant — the guard means fetch is never called

      name: 'x', result: 'PASS', windowFromEpochMillis: 0, windowToEpochMillis: 1,
      sampleCount: 0, objectiveResults: [],
    });

    renderPanel();
    const lookback = screen.getByLabelText('Lookback window in seconds');
    await user.clear(lookback);

    const verifyButton = screen.getByRole('button', { name: /Verify SLO/i });
    // A 0ms window would always be INCONCLUSIVE with no samples, so the control
    // is disabled and the user can never submit it — no request is ever sent.
    expect(verifyButton).toBeDisabled();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('renders the evaluated window timestamps from the verdict', async () => {
    const user = userEvent.setup();
    const from = 1_717_000_000_000;
    const to = 1_717_000_060_000;
    stubVerify({
      name: 'checkout-slo', result: 'PASS', windowFromEpochMillis: from, windowToEpochMillis: to,
      sampleCount: 5,
      objectiveResults: [
        { sli: 'LATENCY_P95', comparator: 'LESS_THAN', threshold: 250, observedValue: 120, result: 'PASS' },
      ],
    });

    renderPanel();
    await user.click(screen.getByRole('button', { name: /Verify SLO/i }));

    const fromStr = new Date(from).toLocaleString();
    const toStr = new Date(to).toLocaleString();
    await waitFor(() => {
      // The caption interleaves the "Evaluated window:" label with the two
      // formatted bounds across text nodes, so match on the element's full text.
      const caption = screen.getByText((_content, node) => {
        const text = node?.textContent ?? '';
        return text.startsWith('Evaluated window:') && text.includes(fromStr) && text.includes(toStr);
      });
      expect(caption).toBeInTheDocument();
    });
  });

  it('lets the user add and remove objectives', async () => {
    const user = userEvent.setup();
    renderPanel();

    expect(screen.queryByLabelText('Objective 3 indicator')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Add objective/i }));
    expect(screen.getByLabelText('Objective 3 indicator')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Remove objective 3' }));
    await waitFor(() => {
      expect(screen.queryByLabelText('Objective 3 indicator')).not.toBeInTheDocument();
    });
  });
});
