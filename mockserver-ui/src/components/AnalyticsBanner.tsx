import { useEffect, useState } from 'react';
import Box from '@mui/material/Box';
import Button from '@mui/material/Button';
import Link from '@mui/material/Link';
import Typography from '@mui/material/Typography';
import {
  isAnalyticsActive,
  isBannerDismissed,
  dismissBanner,
  setAnalyticsOptOut,
  onAnalyticsActivated,
} from '../lib/analytics';

/**
 * Quiet, single-line disclosure note shown once on first dashboard load, after
 * analytics has become active and the user has not yet dismissed it or opted
 * out. Deliberately understated — a muted footer strip, not a prominent Alert —
 * because analytics only ever activates in the official Docker/Helm/binary
 * artefacts (never the plain JAR, embedded use, or local dev), so this is a
 * low-frequency, good-faith notice rather than a consent gate.
 *
 * Visibility is REACTIVE to activation: analytics activates asynchronously (the
 * App awaits getConfiguration() then dynamic-imports posthog-js), so a mount-time
 * snapshot of `isAnalyticsActive()` would always be `false` and the note would
 * never appear. Instead we subscribe via `onAnalyticsActivated` and re-evaluate
 * visibility when activation completes. If analytics is already active at mount,
 * the subscription fires immediately.
 *
 * - "Opt out"  → disables analytics and hides the note permanently.
 * - "Got it"   → records that the disclosure was seen (analytics stays active).
 * - "Learn more" → inline link to the consumer privacy docs page.
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
    <Box
      role="status"
      data-testid="analytics-banner"
      sx={{
        display: 'flex',
        alignItems: 'center',
        flexWrap: 'wrap',
        gap: 0.5,
        px: 1.5,
        py: 0.5,
        flexShrink: 0,
        borderTop: 1,
        borderColor: 'divider',
        color: 'text.secondary',
      }}
    >
      <Typography
        variant="caption"
        sx={{ flexGrow: 1, minWidth: 0, color: 'text.secondary' }}
      >
        MockServer collects anonymous, cookieless dashboard usage stats to improve the UI for
        everyone — no request or mock data is sent.{' '}
        <Link
          href="/mock_server/dashboard_privacy.html"
          target="_blank"
          rel="noopener"
          color="inherit"
          underline="always"
          data-testid="analytics-banner-learn-more"
        >
          Learn more
        </Link>
        .
      </Typography>
      <Button
        color="inherit"
        size="small"
        variant="text"
        onClick={handleOptOut}
        data-testid="analytics-banner-opt-out"
        sx={{ minWidth: 0, fontSize: '0.7rem', textTransform: 'none' }}
      >
        Opt out
      </Button>
      <Button
        color="inherit"
        size="small"
        variant="text"
        onClick={handleDismiss}
        data-testid="analytics-banner-got-it"
        sx={{ minWidth: 0, fontSize: '0.7rem', textTransform: 'none' }}
      >
        Got it
      </Button>
    </Box>
  );
}
