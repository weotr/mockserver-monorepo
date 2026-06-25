// Capture documentation screenshots of every MockServer dashboard tab.
//
// This drives a *running* demo dashboard with headless Chromium and writes one
// PNG per tab. It does NOT start the demo itself — bring the dashboard up first
// with `npm run demo` (see scripts/launch-with-demo-data.sh) so every panel is
// populated with representative data, then run this against it.
//
// The capture geometry matches the existing website screenshots: a 1920-wide
// viewport at deviceScaleFactor 2, i.e. ~3840px-wide Retina PNGs, so new shots
// are as crisp as the ones already on www.mock-server.com.
//
// Usage:
//   node scripts/capture-dashboard-screenshots.mjs
//   ONLY=chaos,metrics node scripts/capture-dashboard-screenshots.mjs
//   FULL_PAGE=true OUT_DIR=/tmp/shots node scripts/capture-dashboard-screenshots.mjs
//
// Env (all optional):
//   UI_PORT      dev-server port the dashboard is served on   (default 3000)
//   MS_PORT      MockServer control-plane port (?port=)        (default 1080)
//   OUT_DIR      directory to write PNGs into                  (default jekyll-www.mock-server.com/images)
//   ONLY         comma-separated tab values to capture         (default all)
//   WIDTH        CSS viewport width                            (default 1920)
//   HEIGHT       CSS viewport height                           (default 900)
//   SCALE        deviceScaleFactor (Retina = 2)                (default 2)
//   FULL_PAGE    "true" to capture the whole scroll height     (default false)
//   SETTLE_MS    extra settle delay before each capture (ms)   (default 1200)
//   THEME        "light" or "dark" colour scheme               (default light)

import { chromium } from 'playwright';
import { fileURLToPath } from 'node:url';
import { dirname, resolve, join } from 'node:path';
import { mkdir } from 'node:fs/promises';

const __dirname = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dirname, '..', '..');

const UI_PORT = process.env.UI_PORT || '3000';
const MS_PORT = process.env.MS_PORT || '1080';
const OUT_DIR = process.env.OUT_DIR
  ? resolve(process.env.OUT_DIR)
  : join(repoRoot, 'jekyll-www.mock-server.com', 'images');
const WIDTH = Number(process.env.WIDTH || 1920);
const HEIGHT = Number(process.env.HEIGHT || 900);
const SCALE = Number(process.env.SCALE || 2);
const FULL_PAGE = process.env.FULL_PAGE === 'true';
const SETTLE_MS = Number(process.env.SETTLE_MS || 1200);
const THEME = process.env.THEME === 'dark' ? 'dark' : 'light';
const ONLY = (process.env.ONLY || '').split(',').map((s) => s.trim()).filter(Boolean);

const DASHBOARD_URL = `http://localhost:${UI_PORT}/mockserver/dashboard/?port=${MS_PORT}`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// One entry per dashboard tab. `value` + `ariaLabel` mirror NAV_TABS in
// src/components/AppBar.tsx; `file` follows the website's MockServer<Name>.png
// convention (existing names reused so docs pages need no <img> edits).
//   lazy      — panel is React.lazy-loaded and shows a "Loading…" placeholder
//               that must clear before we shoot.
//   settleMs  — extra dwell before the shot, for panels that keep filling in
//               after mount (the Metrics/Performance time-series charts render
//               "collecting…" until they have a few sampling intervals; gRPC,
//               LLM Optimise, and Sessions fetch/group their data async).
//   prepare   — interactions to reach a richer documentation state before the
//               shot (open the Advanced editor, select an LLM conversation,
//               expand the HTTP chaos form). Best-effort: a failure is logged
//               and the capture still happens.
const CHART_SETTLE = Number(process.env.CHART_SETTLE_MS || 8000);
const SLOW_SETTLE = Number(process.env.SLOW_SETTLE_MS || 6000);

const TABS = [
  { value: 'get-started',  ariaLabel: 'Get started view',          file: 'MockServerGetStarted.png' },
  { value: 'dashboard',    ariaLabel: 'Dashboard view',            file: 'MockServerDashboard.png' },
  {
    value: 'traffic', ariaLabel: 'Traffic inspector view', file: 'MockServerTrafficInspector.png', settleMs: SLOW_SETTLE,
    // Open an LLM exchange and show its conversation, not a bare HTTP row.
    prepare: async (page) => {
      const row = page.getByText('/v1/messages', { exact: false }).first();
      await row.waitFor({ state: 'visible', timeout: 10000 });
      await row.click();
      const convo = page.getByRole('tab', { name: 'Conversation' });
      await convo.waitFor({ state: 'visible', timeout: 8000 });
      await convo.click();
    },
  },
  { value: 'breakpoints',  ariaLabel: 'Breakpoints view',          file: 'MockServerBreakpoints.png' },
  {
    value: 'composer', ariaLabel: 'Mocks view', file: 'MockServerComposer.png', lazy: true, settleMs: SLOW_SETTLE,
    // Show the full Advanced expectation editor rather than Quick mode.
    prepare: async (page) => {
      const advanced = page.locator('[aria-label="Advanced"]').first();
      await advanced.waitFor({ state: 'visible', timeout: 8000 });
      await advanced.click();
    },
  },
  {
    value: 'chaos', ariaLabel: 'Service chaos view', file: 'MockServerChaos.png',
    // Expand the HTTP Service Chaos section so its form fields show, while the
    // other high-level sections stay collapsed.
    prepare: async (page) => {
      const header = page.getByText('HTTP Service Chaos', { exact: false }).first();
      await header.waitFor({ state: 'visible', timeout: 8000 });
      const expand = page.getByRole('button', { name: 'Expand HTTP chaos' });
      if (await expand.isVisible().catch(() => false)) await expand.click();
      else await header.click();
    },
  },
  {
    value: 'performance', ariaLabel: 'Performance testing view', file: 'MockServerPerformance.png', lazy: true, settleMs: CHART_SETTLE,
    // Show the RUNNING load scenario (live charts), not the empty create form.
    // The panel auto-renders the live-status section once GET /mockserver/loadScenario
    // reports state=running — so just wait for it. Shoot this EARLY in the load
    // run (during ramp): at sustained peak the status endpoint is starved and the
    // panel falls back to the create form. If it never appears, we still capture.
    prepare: async (page) => {
      await page.locator('[data-testid="load-live-status"]').first()
        .waitFor({ state: 'visible', timeout: 20000 });
    },
  },
  { value: 'optimise',     ariaLabel: 'LLM Optimise view',         file: 'MockServerOptimise.png',     lazy: true, settleMs: SLOW_SETTLE },
  { value: 'async',        ariaLabel: 'AsyncAPI broker mock view', file: 'MockServerAsyncAPI.png' },
  { value: 'grpc',         ariaLabel: 'gRPC services view',        file: 'MockServerGRPC.png',         settleMs: SLOW_SETTLE },
  { value: 'sessions',     ariaLabel: 'Trace inspector view',      file: 'MockServerSessions.png',     settleMs: SLOW_SETTLE },
  { value: 'library',      ariaLabel: 'Library of captured content', file: 'MockServerLibrary.png' },
  { value: 'drift',        ariaLabel: 'Drift detection view',      file: 'MockServerDrift.png' },
  { value: 'verification', ariaLabel: 'Verification view',         file: 'MockServerVerification.png' },
  { value: 'contract',     ariaLabel: 'Contract test view',        file: 'MockServerContract.png' },
  { value: 'cluster',      ariaLabel: 'Cluster status view',       file: 'MockServerCluster.png' },
  { value: 'metrics',      ariaLabel: 'Metrics view',              file: 'MockServerMetrics.png',      lazy: true, settleMs: CHART_SETTLE },
];

// Navigate to a tab. The inline ToggleButtons carry data-nav-tab; the compact
// hamburger and the "More" overflow menus only carry aria-label on their items,
// so fall back to opening whichever menu is present and clicking by aria-label.
async function gotoTab(page, tab) {
  const toggle = page.locator(`[data-nav-tab="${tab.value}"]`).first();
  if (await toggle.isVisible().catch(() => false)) {
    await toggle.click();
    return;
  }
  const compact = page.locator('[aria-label="Open navigation menu"]').first();
  const more = page.locator('[aria-label="More views"]').first();
  if (await compact.isVisible().catch(() => false)) {
    await compact.click();
  } else if (await more.isVisible().catch(() => false)) {
    await more.click();
  } else {
    throw new Error(`No way to reach tab "${tab.value}" — neither inline button nor a nav menu is visible`);
  }
  await page.locator(`[role="menuitem"][aria-label="${tab.ariaLabel}"]`).first().click();
}

async function main() {
  await mkdir(OUT_DIR, { recursive: true });

  const wanted = ONLY.length ? TABS.filter((t) => ONLY.includes(t.value)) : TABS;
  if (!wanted.length) {
    throw new Error(`ONLY=${process.env.ONLY} matched no tabs. Valid values: ${TABS.map((t) => t.value).join(', ')}`);
  }

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({
    viewport: { width: WIDTH, height: HEIGHT },
    deviceScaleFactor: SCALE,
    colorScheme: THEME,
  });
  const page = await context.newPage();

  console.log(`→ Opening ${DASHBOARD_URL}`);
  await page.goto(DASHBOARD_URL, { waitUntil: 'domcontentloaded' });

  // Wait for the nav to exist (app booted), then for the WebSocket to actually
  // connect — the dashboard fills the traffic/log/expectation stores from the
  // server's snapshot only once the header flips from "Connecting" to
  // "Connected". Capturing before that yields empty panels.
  await page.waitForSelector('[data-nav-tab], [aria-label="Open navigation menu"]', { timeout: 30000 });
  await page
    .waitForFunction(() => /\bConnected\b/.test(document.body.innerText || ''), null, { timeout: 30000 })
    .catch(() => console.warn('    ! header never showed "Connected" — panels may be empty (is load injection saturating the connection?)'));
  await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
  await sleep(2000);

  let ok = 0;
  for (const tab of wanted) {
    try {
      await gotoTab(page, tab);
      // Lazy panels render a "Loading …" placeholder first; let it clear.
      if (tab.lazy) {
        await page
          .getByText(/Loading/i)
          .first()
          .waitFor({ state: 'hidden', timeout: 20000 })
          .catch(() => {});
      }
      await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
      // Drive the tab into a richer documentation state (best-effort).
      if (tab.prepare) {
        try {
          await tab.prepare(page);
        } catch (err) {
          console.warn(`    ! ${tab.value} prepare step skipped: ${err.message.split('\n')[0]}`);
        }
      }
      // Charts (Metrics/Performance) keep drawing as samples arrive — give those
      // tabs longer to finish "collecting…" before the shot.
      await page.getByText(/collecting/i).first().waitFor({ state: 'hidden', timeout: 12000 }).catch(() => {});
      await sleep(tab.settleMs || SETTLE_MS);

      const out = join(OUT_DIR, tab.file);
      await page.screenshot({ path: out, fullPage: FULL_PAGE });
      console.log(`  ✓ ${tab.value.padEnd(13)} → ${out}`);
      ok++;
    } catch (err) {
      console.error(`  ✗ ${tab.value.padEnd(13)} FAILED: ${err.message}`);
    }
  }

  await browser.close();
  console.log(`\nCaptured ${ok}/${wanted.length} tab(s) into ${OUT_DIR} at ${WIDTH}x${HEIGHT}@${SCALE}x`);
  if (ok < wanted.length) process.exitCode = 1;
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
