// @ts-check
const { defineConfig } = require('@playwright/test');

/**
 * Playwright Test configuration for MockServer browser client tests.
 *
 * These tests verify that mockServerClient.js works inside a real browser
 * (XHR/fetch + CORS to MockServer's control-plane API).
 *
 * MockServer must be running externally with CORS enabled before tests start.
 * The tests connect to http://localhost:{MOCKSERVER_PORT:-1080}.
 */
module.exports = defineConfig({
  testDir: '.',
  testMatch: '*.spec.js',
  /* Each test creates expectations and fires XHR — give them time */
  timeout: 60_000,
  /* Run tests serially: they share a single MockServer instance */
  workers: 1,
  /* Only Chromium — matches the old Karma Chrome-only setup */
  projects: [
    {
      name: 'chromium',
      use: {
        browserName: 'chromium',
        /* Allow insecure localhost for the MockServer self-signed cert */
        ignoreHTTPSErrors: true,
        /* Run without Chromium's setuid sandbox so the test works in a non-root,
         * capability-dropped CI container (the container is the isolation boundary,
         * so we don't grant SYS_ADMIN to untrusted PR code). */
        launchOptions: { chromiumSandbox: false },
      },
    },
  ],
  reporter: [['list']],
});
