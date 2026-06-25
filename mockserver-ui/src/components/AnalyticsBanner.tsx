import { useEffect, useState } from 'react';
import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import Link from '@mui/material/Link';
import {
  isAnalyticsActive,
  isBannerDismissed,
  dismissBanner,
  setAnalyticsOptOut,
  onAnalyticsActivated,
} from '../lib/analytics';

/**
 * Small, dismissible disclosure banner shown on first dashboard load once
 * analytics has become active and the user has not yet dismissed it or opted
 * out.
 *
 * Visibility is REACTIVE to activation: analytics activates asynchronously (the
 * App awaits getConfiguration() then dynamic-imports posthog-js), so a mount-time
 * snapshot of `isAnalyticsActive()` would always be `false` and the banner would
 * never appear. Instead we subscribe via `onAnalyticsActivated` and re-evaluate
 * visibility when activation completes. If analytics is already active at mount,
 * the subscription fires immediately.
 *
 * - "Opt out"  → disables analytics and hides the banner permanently.
 * - "Got it"   → records that the disclosure was seen (analytics stays active).
 * - "Learn more" → links to the consumer privacy docs page.
 */
export default function AnalyticsBanner() {
  const [visible, setVisible] = useState<boolean>(() => {
    // Covers the rare case where analytics is already active at first render
    // (e.g. a remount after activation): show immediately if not dismissed.
    try {
      return isAnalyticsActive() && !isBannerDismissed();
    } catch {
      return false;
    }
  });

  useEffect(() => {
    let cancelled = false;
    const reevaluate = () => {
      if (cancelled) return;
      try {
        if (isAnalyticsActive() && !isBannerDismissed()) setVisible(true);
      } catch {
        /* swallow — never break render */
      }
    };
    // Fire once now (handles already-active) and on the activation notification.
    reevaluate();
    const unsubscribe = onAnalyticsActivated(reevaluate);
    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, []);

  if (!visible) return null;

  const handleDismiss = () => {
    dismissBanner();
    setVisible(false);
  };

  const handleOptOut = () => {
    setAnalyticsOptOut(true);
    dismissBanner();
    setVisible(false);
  };

  return (
    <Alert
      severity="info"
      role="status"
      data-testid="analytics-banner"
      onClose={handleDismiss}
      sx={{ mx: 1, mt: 1, flexShrink: 0 }}
      action={
        <>
          <Button
            color="inherit"
            size="small"
            href="/mock_server/dashboard_privacy.html"
            target="_blank"
            rel="noopener"
            data-testid="analytics-banner-learn-more"
          >
            Learn more
          </Button>
          <Button
            color="inherit"
            size="small"
            onClick={handleOptOut}
            data-testid="analytics-banner-opt-out"
          >
            Opt out
          </Button>
          <Button
            color="inherit"
            size="small"
            onClick={handleDismiss}
            data-testid="analytics-banner-got-it"
          >
            Got it
          </Button>
        </>
      }
    >
      MockServer collects anonymous, cookieless dashboard usage stats to improve the UI. No
      request or mock data is ever sent, and no tracking cookie is set.{' '}
      <Link href="/mock_server/dashboard_privacy.html" target="_blank" rel="noopener" color="inherit">
        Learn more
      </Link>
      .
    </Alert>
  );
}
