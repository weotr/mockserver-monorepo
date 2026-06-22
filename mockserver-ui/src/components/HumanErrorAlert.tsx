import { useState } from 'react';
import Alert, { type AlertProps } from '@mui/material/Alert';
import Box from '@mui/material/Box';
import Collapse from '@mui/material/Collapse';
import Link from '@mui/material/Link';
import type { SxProps, Theme } from '@mui/material/styles';
import type { HumanError } from '../lib/errorMessage';

interface HumanErrorAlertProps {
  /**
   * The humanised error to render. Either pass a {@link HumanError} object or
   * the discrete `message` / `details` props below — `error` takes precedence.
   */
  error?: HumanError;
  /** Short, user-facing message (used when `error` is not provided). */
  message?: string;
  /** Raw underlying text shown behind the "Details" expander. */
  details?: string;
  /** Alert severity. Defaults to `error`. */
  severity?: AlertProps['severity'];
  /** Alert variant (e.g. `outlined`). Passed straight to MUI Alert. */
  variant?: AlertProps['variant'];
  /** Optional close handler — renders the Alert's close (X) button. */
  onClose?: AlertProps['onClose'];
  sx?: SxProps<Theme>;
  /** Forwarded as the Alert's `data-testid` for targeted assertions. */
  'data-testid'?: string;
}

/**
 * Shared error alert with an inline "Details" expander. Consolidates the three
 * near-identical inline implementations that previously lived in ComposerView,
 * CaptureAsMockDialog, and ImportForm: a short humanised `message` with the raw
 * `details` text revealed behind a "Details" / "Hide details" toggle in a
 * monospace, scrollable block.
 */
export default function HumanErrorAlert({
  error,
  message,
  details,
  severity = 'error',
  variant,
  onClose,
  sx,
  'data-testid': dataTestId,
}: HumanErrorAlertProps) {
  const [detailsOpen, setDetailsOpen] = useState(false);

  const resolvedMessage = error?.message ?? message ?? '';
  const resolvedDetails = error?.details ?? details;

  return (
    <Alert
      severity={severity}
      variant={variant}
      onClose={onClose}
      sx={sx}
      data-testid={dataTestId}
    >
      {resolvedMessage}
      {resolvedDetails && (
        <Box sx={{ mt: 0.5 }}>
          <Link
            component="button"
            type="button"
            variant="caption"
            underline="hover"
            onClick={() => setDetailsOpen((o) => !o)}
            sx={{ color: 'inherit' }}
          >
            {detailsOpen ? 'Hide details' : 'Details'}
          </Link>
          <Collapse in={detailsOpen} unmountOnExit>
            <Box
              component="pre"
              sx={{
                mt: 0.5,
                mb: 0,
                p: 1,
                fontFamily: 'monospace',
                fontSize: '0.72rem',
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
                bgcolor: 'action.hover',
                borderRadius: 1,
                maxHeight: 240,
                overflow: 'auto',
              }}
            >
              {resolvedDetails}
            </Box>
          </Collapse>
        </Box>
      )}
    </Alert>
  );
}
