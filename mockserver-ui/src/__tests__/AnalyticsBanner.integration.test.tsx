/**
 * Integration test driving the REAL analytics module (not a mock of it) through
 * the production order: the banner mounts while analytics is still INACTIVE
 * (activation is async — App awaits getConfiguration() then dynamic-imports
 * posthog-js), then activation completes and the banner must appear reactively.
 *
 * This is the regression guard for the MAJOR bug where the banner computed its
 * visibility once via a mount-time `useState` initializer and never recomputed,
 * so it never rendered in production even though `app_open` telemetry fired.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

// Mock only posthog-js and the store — the analytics module under test is REAL.
const initMock = vi.fn();
const captureMock = vi.fn();
const optOutMock = vi.fn();
vi.mock('posthog-js', () => ({
  default: {
    init: (...a: unknown[]) => initMock(...a),
    capture: (...a: unknown[]) => captureMock(...a),
    opt_out_capturing: (...a: unknown[]) => optOutMock(...a),
  },
}));
vi.mock('../store', () => ({
  useDashboardStore: { getState: () => ({ themeMode: 'dark' }) },
}));

import AnalyticsBanner from '../components/AnalyticsBanner';
import { initAnalytics, __resetAnalyticsForTests } from '../lib/analytics';
import type { Configuration } from '../lib/configuration';

const ACTIVE_CONFIG: Configuration = {
  dashboardAnalyticsEnabled: true,
  dashboardAnalyticsEndpoint: 'https://posthog.example.com',
  dashboardAnalyticsKey: 'phc_test_key',
};

beforeEach(() => {
  __resetAnalyticsForTests();
  initMock.mockClear();
  captureMock.mockClear();
  optOutMock.mockClear();
  localStorage.clear();
  Object.defineProperty(navigator, 'doNotTrack', { value: null, configurable: true });
  Object.defineProperty(navigator, 'onLine', { value: true, configurable: true });
  Object.defineProperty(navigator, 'webdriver', { value: false, configurable: true });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AnalyticsBanner + real analytics activation', () => {
  it('appears only after async activation completes (MAJOR regression guard)', async () => {
    render(<AnalyticsBanner />);
    // At mount analytics is inactive — the banner must NOT be shown yet.
    expect(screen.queryByTestId('analytics-banner')).not.toBeInTheDocument();

    // Activation happens asynchronously, exactly as App does after fetching config.
    initAnalytics(ACTIVE_CONFIG, { validViews: ['dashboard'], initialView: 'dashboard' });

    // Once activation resolves (dynamic import + init), the banner appears.
    await waitFor(() => {
      expect(screen.getByTestId('analytics-banner')).toBeInTheDocument();
    });
    expect(initMock).toHaveBeenCalledTimes(1);
  });

  it('does NOT appear when activation never happens (gates fail)', async () => {
    render(<AnalyticsBanner />);
    initAnalytics({ ...ACTIVE_CONFIG, dashboardAnalyticsEnabled: false }, { validViews: ['dashboard'] });
    // Give the (non-)activation path time to settle.
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.queryByTestId('analytics-banner')).not.toBeInTheDocument();
    expect(initMock).not.toHaveBeenCalled();
  });

  it('opt-out hides the banner after it has appeared', async () => {
    const user = userEvent.setup();
    render(<AnalyticsBanner />);
    initAnalytics(ACTIVE_CONFIG, { validViews: ['dashboard'], initialView: 'dashboard' });
    await waitFor(() => expect(screen.getByTestId('analytics-banner')).toBeInTheDocument());

    await user.click(screen.getByTestId('analytics-banner-opt-out'));
    expect(screen.queryByTestId('analytics-banner')).not.toBeInTheDocument();
    expect(localStorage.getItem('mockserver.analytics.optOut')).toBe('true');
    expect(optOutMock).toHaveBeenCalledTimes(1);
  });

  it('dismiss ("Got it") hides the banner and records the seen flag', async () => {
    const user = userEvent.setup();
    render(<AnalyticsBanner />);
    initAnalytics(ACTIVE_CONFIG, { validViews: ['dashboard'], initialView: 'dashboard' });
    await waitFor(() => expect(screen.getByTestId('analytics-banner')).toBeInTheDocument());

    await user.click(screen.getByTestId('analytics-banner-got-it'));
    expect(screen.queryByTestId('analytics-banner')).not.toBeInTheDocument();
    expect(localStorage.getItem('mockserver.analytics.bannerDismissed')).toBe('true');
    // Got it must NOT opt out — analytics stays active.
    expect(optOutMock).not.toHaveBeenCalled();
  });

  it('does not re-show after dismissal when re-mounted while still active', async () => {
    const user = userEvent.setup();
    const { unmount } = render(<AnalyticsBanner />);
    initAnalytics(ACTIVE_CONFIG, { validViews: ['dashboard'], initialView: 'dashboard' });
    await waitFor(() => expect(screen.getByTestId('analytics-banner')).toBeInTheDocument());
    await user.click(screen.getByTestId('analytics-banner-got-it'));
    unmount();

    // Re-mount: analytics is still active, but the dismissed flag must suppress it.
    render(<AnalyticsBanner />);
    await new Promise((r) => setTimeout(r, 30));
    expect(screen.queryByTestId('analytics-banner')).not.toBeInTheDocument();
  });
});
