import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import LoadScenarioPanel from '../components/LoadScenarioPanel';

const params = { host: '127.0.0.1', port: '1080', secure: false };

afterEach(() => {
  vi.restoreAllMocks();
});

/**
 * Pick an option from a MUI (non-native) Select. fireEvent.change can't drive these — the visible
 * combobox is a div, so open it (mouseDown) and click the option in the portal-rendered listbox.
 */
function selectOption(combobox: HTMLElement, optionName: string | RegExp) {
  fireEvent.mouseDown(combobox);
  const option = within(screen.getByRole('listbox')).getByRole('option', { name: optionName });
  fireEvent.click(option);
}

/** A fetch mock that returns `none` for GET and records PUT/DELETE calls. */
function stubFetch(getBody: unknown, status = 200) {
  const calls: Array<{ url: string; method: string; body?: unknown }> = [];
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ url: String(url), method, body });
    if (method === 'GET') {
      return { ok: status === 200, status, json: async () => getBody } as unknown as Response;
    }
    return { ok: true, status: 200, json: async () => ({ status: method === 'DELETE' ? 'stopped' : 'started' }) } as unknown as Response;
  });
  vi.stubGlobal('fetch', fetchMock);
  return { calls, fetchMock };
}

/**
 * A fetch mock that distinguishes the registry-list GET (returns `{scenarios}`) from
 * the legacy status GET (returns `{state:'none'}`). Both hit `/mockserver/loadScenario`,
 * so the stub returns the registry shape for plain GETs; PUT/DELETE are recorded.
 */
function stubRegistry(scenarios: unknown[]) {
  const calls: Array<{ url: string; method: string; body?: unknown }> = [];
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const body = init?.body ? JSON.parse(init.body as string) : undefined;
    calls.push({ url: String(url), method, body });
    if (method === 'GET') {
      return { ok: true, status: 200, json: async () => ({ state: 'none', scenarios }) } as unknown as Response;
    }
    return { ok: true, status: 200, json: async () => ({ status: 'ok' }) } as unknown as Response;
  });
  vi.stubGlobal('fetch', fetchMock);
  return { calls, fetchMock };
}

describe('LoadScenarioPanel', () => {
  it('renders the author form with VELOCITY/MUSTACHE template options', async () => {
    stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    expect(screen.getByLabelText(/Scenario name/)).toBeInTheDocument();
    // template type select offers only Velocity + Mustache
    expect(screen.getByText('Velocity')).toBeInTheDocument();
  });

  it('builds a valid PUT body with a staged profile from the form', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/Scenario name/), { target: { value: 'checkout-load' } });

    // Default seeded profile is a VU ramp (stage 0) then a VU hold (stage 1). Tweak the hold to 8 VUs.
    const stage1 = screen.getByTestId('load-stage-1');
    fireEvent.change(within(stage1).getByLabelText('Virtual users (VUs)'), { target: { value: '8' } });
    fireEvent.change(within(stage1).getByLabelText('Duration (ms)'), { target: { value: '20000' } });

    const step0 = screen.getByTestId('load-step-0');
    fireEvent.change(within(step0).getByLabelText('Target host'), { target: { value: 'target.svc' } });
    fireEvent.change(within(step0).getByLabelText('Target port'), { target: { value: '8080' } });
    fireEvent.change(within(step0).getByLabelText('Path'), { target: { value: '/api/item' } });

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));

    await waitFor(() => {
      const put = calls.find((c) => c.method === 'PUT');
      expect(put).toBeTruthy();
    });
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.url).toContain('/mockserver/loadScenario');
    expect(put.body).toMatchObject({
      name: 'checkout-load',
      templateType: 'VELOCITY',
      profile: {
        stages: [
          { type: 'VU', startVus: 1, endVus: 10, durationMillis: 30000, curve: 'LINEAR' },
          { type: 'VU', vus: 8, durationMillis: 20000 },
        ],
      },
      steps: [
        {
          request: {
            method: 'GET',
            path: '/api/item',
            socketAddress: { host: 'target.svc', port: 8080, scheme: 'HTTP' },
          },
        },
      ],
    });
    // The hold stage must NOT carry ramp-only fields.
    const stages = (put.body as { profile: { stages: Array<Record<string, unknown>> } }).profile.stages;
    expect(stages[1]).not.toHaveProperty('startVus');
    expect(stages[1]).not.toHaveProperty('curve');
    // No header rows were added — `headers` must be omitted to keep the body minimal.
    const builtStep = (put.body as { steps: Array<{ request: Record<string, unknown> }> }).steps[0]!;
    expect(builtStep.request).not.toHaveProperty('headers');
  });

  it('builds profile.stages including a RATE stage and a PAUSE stage', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/Scenario name/), { target: { value: 'rate-load' } });

    // Stage 1 (the seeded hold) → make it a RATE hold of 50/s.
    const stage1 = screen.getByTestId('load-stage-1');
    selectOption(within(stage1).getByLabelText('Stage type'), 'Rate (iterations/sec)');
    fireEvent.change(within(stage1).getByLabelText('Rate (iterations/sec)'), { target: { value: '50' } });
    fireEvent.change(within(stage1).getByLabelText('Max VUs (optional)'), { target: { value: '20' } });
    fireEvent.change(within(stage1).getByLabelText('Duration (ms)'), { target: { value: '15000' } });

    // Add a third stage and make it a PAUSE.
    fireEvent.click(screen.getByRole('button', { name: /Add stage/i }));
    const stage2 = screen.getByTestId('load-stage-2');
    selectOption(within(stage2).getByLabelText('Stage type'), 'Pause (no load)');
    fireEvent.change(within(stage2).getByLabelText('Duration (ms)'), { target: { value: '5000' } });

    const step0 = screen.getByTestId('load-step-0');
    fireEvent.change(within(step0).getByLabelText('Target host'), { target: { value: 'target.svc' } });
    fireEvent.change(within(step0).getByLabelText('Target port'), { target: { value: '8080' } });
    fireEvent.change(within(step0).getByLabelText('Path'), { target: { value: '/api/item' } });

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    const stages = (put.body as { profile: { stages: Array<Record<string, unknown>> } }).profile.stages;
    expect(stages).toHaveLength(3);
    expect(stages[0]).toMatchObject({ type: 'VU', startVus: 1, endVus: 10 });
    expect(stages[1]).toMatchObject({ type: 'RATE', rate: 50, maxVus: 20, durationMillis: 15000 });
    expect(stages[2]).toMatchObject({ type: 'PAUSE', durationMillis: 5000 });
    // The PAUSE stage carries only its duration.
    expect(stages[2]).not.toHaveProperty('vus');
    expect(stages[2]).not.toHaveProperty('rate');
  });

  it('reorders and removes stages', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/Scenario name/), { target: { value: 'reorder-load' } });
    // Move the hold stage (stage 2) up so it runs first.
    fireEvent.click(screen.getByRole('button', { name: /Move stage 2 up/i }));

    const step0 = screen.getByTestId('load-step-0');
    fireEvent.change(within(step0).getByLabelText('Target host'), { target: { value: 'target.svc' } });
    fireEvent.change(within(step0).getByLabelText('Target port'), { target: { value: '8080' } });
    fireEvent.change(within(step0).getByLabelText('Path'), { target: { value: '/api/item' } });

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    const stages = (put.body as { profile: { stages: Array<Record<string, unknown>> } }).profile.stages;
    // After moving up, the hold (vus: 10) is now first, the ramp second.
    expect(stages[0]).toMatchObject({ type: 'VU', vus: 10 });
    expect(stages[1]).toMatchObject({ type: 'VU', startVus: 1, endVus: 10 });
  });

  it('emits per-step request headers in MockServer KeyToMultiValue object-map shape', async () => {
    const { calls } = stubFetch({ state: 'none' });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());

    fireEvent.change(screen.getByLabelText(/Scenario name/), { target: { value: 'auth-load' } });
    // The seeded default profile (a VU ramp then hold) is already valid; no profile edits needed.

    const step0 = screen.getByTestId('load-step-0');
    fireEvent.change(within(step0).getByLabelText('Target host'), { target: { value: 'api.svc' } });
    fireEvent.change(within(step0).getByLabelText('Target port'), { target: { value: '443' } });
    fireEvent.change(within(step0).getByLabelText('Path'), { target: { value: '/secure' } });

    fireEvent.click(within(step0).getByRole('button', { name: /Add header/i }));
    fireEvent.change(within(step0).getByLabelText('Header name'), { target: { value: 'Authorization' } });
    fireEvent.change(within(step0).getByLabelText('Header value'), { target: { value: 'Bearer xyz' } });

    fireEvent.click(screen.getByRole('button', { name: /^Load$/i }));

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect(put.body).toMatchObject({
      steps: [
        {
          request: {
            path: '/secure',
            headers: { Authorization: ['Bearer xyz'] },
          },
        },
      ],
    });
  });

  it('reloads a definition that already carries request headers into the header rows on Edit', async () => {
    stubFetch({
      state: 'running',
      name: 'header-run',
      runId: 'rH',
      currentVus: 2,
      requestsSent: 5,
      succeeded: 5,
      failed: 0,
      p50Millis: 5,
      p95Millis: 9,
      p99Millis: 12,
      elapsedMillis: 1000,
      definition: {
        name: 'header-run',
        templateType: 'VELOCITY',
        profile: { stages: [{ type: 'VU', vus: 2, durationMillis: 10000 }] },
        steps: [
          {
            request: {
              method: 'GET',
              path: '/secure',
              headers: { Authorization: ['Bearer abc'], 'X-Tenant': ['acme'] },
              socketAddress: { host: 'api.svc', port: 443, scheme: 'HTTPS' },
            },
          },
        ],
      },
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-live-status')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Edit running/i }));

    await waitFor(() =>
      expect((screen.getByLabelText(/Scenario name/) as HTMLInputElement).value).toBe('header-run'),
    );
    const step0 = screen.getByTestId('load-step-0');
    const names = within(step0).getAllByLabelText('Header name') as HTMLInputElement[];
    const values = within(step0).getAllByLabelText('Header value') as HTMLInputElement[];
    expect(names.map((n) => n.value)).toEqual(['Authorization', 'X-Tenant']);
    expect(values.map((v) => v.value)).toEqual(['Bearer abc', 'acme']);
  });

  it('shows live status from a mocked running GET', async () => {
    stubFetch({
      state: 'running',
      name: 'live-run',
      runId: 'r1',
      currentVus: 4,
      requestsSent: 1000,
      succeeded: 950,
      failed: 50,
      p50Millis: 12,
      p95Millis: 88,
      p99Millis: 140,
      elapsedMillis: 5000,
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-live-status')).toBeInTheDocument());
    const live = screen.getByTestId('load-live-status');
    expect(within(live).getByText('1,000')).toBeInTheDocument(); // requests sent
    expect(within(live).getByText('5.0%')).toBeInTheDocument(); // error rate 50/1000
    expect(screen.getByRole('button', { name: /Stop/i })).toBeInTheDocument();
  });

  it('shows the active-stage readout from stageIndex/stageType/currentTarget', async () => {
    stubFetch({
      state: 'running',
      name: 'staged-run',
      runId: 'rS',
      currentVus: 12,
      stageIndex: 1,
      stageType: 'RATE',
      currentTarget: 50,
      requestsSent: 200,
      succeeded: 200,
      failed: 0,
      p50Millis: 5,
      p95Millis: 9,
      p99Millis: 12,
      elapsedMillis: 4000,
      definition: {
        name: 'staged-run',
        templateType: 'VELOCITY',
        profile: {
          stages: [
            { type: 'VU', startVus: 1, endVus: 10, durationMillis: 30000, curve: 'LINEAR' },
            { type: 'RATE', rate: 50, durationMillis: 30000 },
            { type: 'PAUSE', durationMillis: 5000 },
          ],
        },
        steps: [{ request: { method: 'GET', path: '/x', socketAddress: { host: 'h', port: 80, scheme: 'HTTP' } } }],
      },
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-live-status')).toBeInTheDocument());
    const readout = screen.getByTestId('load-stage-readout');
    expect(readout).toHaveTextContent('Stage 2/3');
    expect(readout).toHaveTextContent('RATE');
    expect(readout).toHaveTextContent('target 50/s');
  });

  it('toggles a chart series on and off', async () => {
    stubFetch({
      state: 'running', name: 'r', runId: 'r1', currentVus: 2,
      requestsSent: 10, succeeded: 10, failed: 0, p50Millis: 5, p95Millis: 9, p99Millis: 12, elapsedMillis: 1000,
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-chart')).toBeInTheDocument());

    // p99 is not in the default-visible subset.
    const p99Toggle = screen.getByRole('checkbox', { name: 'p99 ms' }) as HTMLInputElement;
    expect(p99Toggle.checked).toBe(false);
    fireEvent.click(p99Toggle);
    expect((screen.getByRole('checkbox', { name: 'p99 ms' }) as HTMLInputElement).checked).toBe(true);

    // RPS is in the default subset — turning it off unchecks it.
    const rpsToggle = screen.getByRole('checkbox', { name: 'RPS' }) as HTMLInputElement;
    expect(rpsToggle.checked).toBe(true);
    fireEvent.click(rpsToggle);
    expect((screen.getByRole('checkbox', { name: 'RPS' }) as HTMLInputElement).checked).toBe(false);
  });

  it('loads the server-echoed definition into the form when editing a run started elsewhere', async () => {
    // A run this tab never started (no in-session lastSubmitted) — the server echoes its full
    // definition so "Edit running" can still populate the author form from it.
    stubFetch({
      state: 'running',
      name: 'foreign-run',
      runId: 'r9',
      currentVus: 3,
      requestsSent: 100,
      succeeded: 100,
      failed: 0,
      p50Millis: 5,
      p95Millis: 9,
      p99Millis: 12,
      elapsedMillis: 2000,
      definition: {
        name: 'foreign-run',
        templateType: 'VELOCITY',
        profile: { stages: [{ type: 'VU', vus: 7, durationMillis: 45000 }] },
        steps: [
          { request: { method: 'GET', path: '/echoed/path', socketAddress: { host: 'echoed.svc', port: 9090, scheme: 'HTTP' } } },
        ],
      },
    });
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-live-status')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: /Edit running/i }));

    // the author form is now populated from the server-provided definition, not any in-tab state
    await waitFor(() =>
      expect((screen.getByLabelText(/Scenario name/) as HTMLInputElement).value).toBe('foreign-run'),
    );
    // The single echoed VU-hold stage round-trips into one stage card with vus 7 / duration 45000.
    const stage0 = screen.getByTestId('load-stage-0');
    expect((within(stage0).getByLabelText('Virtual users (VUs)') as HTMLInputElement).value).toBe('7');
    expect((within(stage0).getByLabelText('Duration (ms)') as HTMLInputElement).value).toBe('45000');
    expect(screen.queryByTestId('load-stage-1')).toBeNull();
    const step0 = screen.getByTestId('load-step-0');
    expect((within(step0).getByLabelText('Path') as HTMLInputElement).value).toBe('/echoed/path');
    expect((within(step0).getByLabelText('Target host') as HTMLInputElement).value).toBe('echoed.svc');
    expect((within(step0).getByLabelText('Target port') as HTMLInputElement).value).toBe('9090');
  });

  it('shows the load-generation-disabled message on a 403', async () => {
    stubFetch({ error: 'load generation not enabled' }, 403);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-disabled-alert')).toBeInTheDocument());
    expect(screen.getByText(/loadGenerationEnabled=true/)).toBeInTheDocument();
    expect(screen.getByText(/MOCKSERVER_LOAD_GENERATION_ENABLED/)).toBeInTheDocument();
  });

  // --- Wave C: registry UX ---

  /** Fill the seeded form's first step so buildScenario succeeds. */
  function fillValidStep(name = 'reg-load') {
    fireEvent.change(screen.getByLabelText(/Scenario name/), { target: { value: name } });
    const step0 = screen.getByTestId('load-step-0');
    fireEvent.change(within(step0).getByLabelText('Target host'), { target: { value: 'target.svc' } });
    fireEvent.change(within(step0).getByLabelText('Target port'), { target: { value: '8080' } });
    fireEvent.change(within(step0).getByLabelText('Path'), { target: { value: '/api/item' } });
  }

  it('lists registered scenarios with their state badges', async () => {
    // The server emits live fields FLAT on each node; the lib parser synthesises `status`.
    stubRegistry([
      { name: 'alpha', state: 'LOADED' },
      { name: 'beta', state: 'RUNNING', requestsSent: 100, succeeded: 100, failed: 0, currentVus: 3 },
      { name: 'gamma', state: 'COMPLETED', requestsSent: 50, succeeded: 50, failed: 0 },
    ]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-registry-row-alpha')).toBeInTheDocument());
    expect(screen.getByTestId('load-registry-state-alpha')).toHaveTextContent('LOADED');
    expect(screen.getByTestId('load-registry-state-beta')).toHaveTextContent('RUNNING');
    expect(screen.getByTestId('load-registry-state-gamma')).toHaveTextContent('COMPLETED');
  });

  it('multi-selects scenarios and starts them with PUT /loadScenario/start {names}', async () => {
    const { calls } = stubRegistry([
      { name: 'alpha', state: 'LOADED' },
      { name: 'beta', state: 'LOADED' },
    ]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-registry-row-alpha')).toBeInTheDocument());

    fireEvent.click(screen.getByLabelText('Select alpha'));
    fireEvent.click(screen.getByLabelText('Select beta'));
    fireEvent.click(screen.getByTestId('load-start-selected'));

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT' && c.url.endsWith('/start'))).toBeTruthy());
    const start = calls.find((c) => c.method === 'PUT' && c.url.endsWith('/start'))!;
    expect(start.body).toEqual({ names: ['alpha', 'beta'] });
  });

  it('starts a single selected scenario with {name} (not {names})', async () => {
    const { calls } = stubRegistry([{ name: 'solo', state: 'LOADED' }]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-registry-row-solo')).toBeInTheDocument());

    fireEvent.click(screen.getByLabelText('Select solo'));
    fireEvent.click(screen.getByTestId('load-start-selected'));

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT' && c.url.endsWith('/start'))).toBeTruthy());
    const start = calls.find((c) => c.method === 'PUT' && c.url.endsWith('/start'))!;
    expect(start.body).toEqual({ name: 'solo' });
  });

  it('stops an individual running scenario with PUT /stop {names:[name]}', async () => {
    const { calls } = stubRegistry([
      { name: 'runner', state: 'RUNNING', requestsSent: 10, succeeded: 10, failed: 0, currentVus: 2 },
    ]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-running-runner')).toBeInTheDocument());

    const running = screen.getByTestId('load-running-runner');
    fireEvent.click(within(running).getByRole('button', { name: /Stop/i }));

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT' && c.url.endsWith('/stop'))).toBeTruthy());
    const stop = calls.find((c) => c.method === 'PUT' && c.url.endsWith('/stop'))!;
    expect(stop.body).toEqual({ names: ['runner'] });
  });

  it('shows multiple running scenarios concurrently', async () => {
    // Live metrics arrive FLAT on each node (the real server shape) and flow through the
    // lib parser into the nested status the panel reads — proving the flat→nested mapping.
    stubRegistry([
      { name: 'r1', state: 'RUNNING', requestsSent: 100, succeeded: 100, failed: 0, currentVus: 3, p95Millis: 40 },
      { name: 'r2', state: 'RUNNING', requestsSent: 200, succeeded: 200, failed: 0, currentVus: 6, p95Millis: 60 },
    ]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-running-r1')).toBeInTheDocument());
    expect(screen.getByTestId('load-running-r2')).toBeInTheDocument();
    expect(screen.getByTestId('load-running-scenarios')).toHaveTextContent('Running now (2)');
    // The flat live fields rendered through into the running card (Active VUs = 3 is unique to r1).
    const r1 = screen.getByTestId('load-running-r1');
    expect(within(r1).getByText('3')).toBeInTheDocument();
  });

  it('deletes one registered scenario via DELETE /loadScenario/{name}', async () => {
    const { calls } = stubRegistry([{ name: 'gone', state: 'LOADED' }]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-registry-row-gone')).toBeInTheDocument());

    fireEvent.click(screen.getByRole('button', { name: 'Delete gone' }));
    await waitFor(() => expect(calls.find((c) => c.method === 'DELETE' && c.url.endsWith('/loadScenario/gone'))).toBeTruthy());
  });

  it('clears all registered scenarios via DELETE /loadScenario', async () => {
    const { calls } = stubRegistry([{ name: 'a', state: 'LOADED' }, { name: 'b', state: 'LOADED' }]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-clear-all')).not.toBeDisabled());

    fireEvent.click(screen.getByTestId('load-clear-all'));
    await waitFor(() =>
      expect(calls.find((c) => c.method === 'DELETE' && c.url.endsWith('/mockserver/loadScenario'))).toBeTruthy(),
    );
  });

  it('"Load" registers without starting (no /start call)', async () => {
    const { calls } = stubRegistry([]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('register-only');

    fireEvent.click(screen.getByTestId('load-register'));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());

    const puts = calls.filter((c) => c.method === 'PUT');
    expect(puts.some((p) => p.url.endsWith('/mockserver/loadScenario'))).toBe(true);
    expect(puts.some((p) => p.url.endsWith('/start'))).toBe(false);
  });

  it('"Load & Run" registers then starts the authored scenario by name', async () => {
    const { calls } = stubRegistry([]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('run-now');

    fireEvent.click(screen.getByTestId('load-register-run'));

    await waitFor(() => expect(calls.find((c) => c.method === 'PUT' && c.url.endsWith('/start'))).toBeTruthy());
    const register = calls.find((c) => c.method === 'PUT' && c.url.endsWith('/mockserver/loadScenario'))!;
    expect((register.body as { name: string }).name).toBe('run-now');
    const start = calls.find((c) => c.method === 'PUT' && c.url.endsWith('/start'))!;
    expect(start.body).toEqual({ name: 'run-now' });
  });

  it('binds the Start delay field to startDelayMillis in the registered body', async () => {
    const { calls } = stubRegistry([]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('delayed');
    fireEvent.change(screen.getByLabelText(/Start delay/), { target: { value: '5000' } });

    fireEvent.click(screen.getByTestId('load-register'));
    await waitFor(() => expect(calls.find((c) => c.method === 'PUT')).toBeTruthy());
    const put = calls.find((c) => c.method === 'PUT')!;
    expect((put.body as { startDelayMillis?: number }).startDelayMillis).toBe(5000);
  });

  // --- Wave B: Code view ---

  it('renders the Code tab with generated register & start snippets', async () => {
    stubRegistry([]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    fillValidStep('code-scenario');

    fireEvent.click(screen.getByRole('tab', { name: 'Code' }));
    await waitFor(() => expect(screen.getByTestId('load-code-review')).toBeInTheDocument());
    // Default tab is Java; the snippet hits both endpoints and names the scenario.
    const review = screen.getByTestId('load-code-review');
    expect(review).toHaveTextContent('/mockserver/loadScenario');
    expect(review).toHaveTextContent('code-scenario');
  });

  it('Code tab explains when the scenario is incomplete', async () => {
    stubRegistry([]);
    render(<LoadScenarioPanel connectionParams={params} />);
    await waitFor(() => expect(screen.getByTestId('load-author-form')).toBeInTheDocument());
    // No name / host filled — buildScenario fails, so the Code tab shows the guidance alert.
    fireEvent.click(screen.getByRole('tab', { name: 'Code' }));
    await waitFor(() => expect(screen.getByTestId('load-code-incomplete')).toBeInTheDocument());
  });
});
