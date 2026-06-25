import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

// Control the analytics module's reported state so we can exercise the banner's
// show/hide logic without touching the real (gated) analytics activation path.
// (A separate test file — AnalyticsBanner.integration.test.tsx — drives the REAL
// analytics module through the async production order.)
const state = { active: true, dismissed: false };
const setAnalyticsOptOut = vi.fn();
const dismissBanner = vi.fn();
vi.mock('../lib/analytics', () => ({
  isAnalyticsActive: () => state.active,
  isBannerDismissed: () => state.dismissed,
  setAnalyticsOptOut: (...args: unknown[]) => setAnalyticsOptOut(...args),
  dismissBanner: (...args: unknown[]) => dismissBanner(...args),
  // Already-active path: fire immediately on subscribe (matches real semantics).
  onAnalyticsActivated: (listener: () => void) => {
    if (state.active) listener();
    return () => {};
  },
}));

import AnalyticsBanner from '../components/AnalyticsBanner';

beforeEach(() => {
  state.active = true;
  state.dismissed = false;
  setAnalyticsOptOut.mockClear();
  dismissBanner.mockClear();
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AnalyticsBanner', () => {
  it('shows when analytics is active and not yet dismissed', () => {
    render(<AnalyticsBanner />);
    expect(screen.getByTestId('analytics-banner')).toBeInTheDocument();
    expect(screen.getByText(/anonymous, cookieless dashboard usage stats/i)).toBeInTheDocument();
  });

  it('does NOT show when analytics is inactive', () => {
    state.active = false;
    render(<AnalyticsBanner />);
    expect(screen.queryByTestId('analytics-banner')).not.toBeInTheDocument();
  });

  it('does NOT show when already dismissed', () => {
    state.dismissed = true;
    render(<AnalyticsBanner />);
    expect(screen.queryByTestId('analytics-banner')).not.toBeInTheDocument();
  });

  it('"Opt out" opts out, records dismissal, and hides the banner', async () => {
    const user = userEvent.setup();
    render(<AnalyticsBanner />);
    await user.click(screen.getByTestId('analytics-banner-opt-out'));
    expect(setAnalyticsOptOut).toHaveBeenCalledWith(true);
    expect(dismissBanner).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('analytics-banner')).not.toBeInTheDocument();
  });

  it('"Got it" records dismissal (without opting out) and hides the banner', async () => {
    const user = userEvent.setup();
    render(<AnalyticsBanner />);
    await user.click(screen.getByTestId('analytics-banner-got-it'));
    expect(dismissBanner).toHaveBeenCalledTimes(1);
    expect(setAnalyticsOptOut).not.toHaveBeenCalled();
    expect(screen.queryByTestId('analytics-banner')).not.toBeInTheDocument();
  });

  it('"Learn more" links to the consumer privacy docs page', () => {
    render(<AnalyticsBanner />);
    const link = screen.getByTestId('analytics-banner-learn-more');
    expect(link).toHaveAttribute('href', '/mock_server/dashboard_privacy.html');
  });
});
