import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Alert, AlertTitle, Box, Button, Stack, Typography } from '@mui/material';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutlineOutlined';
import RefreshIcon from '@mui/icons-material/Refresh';

interface ErrorBoundaryProps {
  /** Short label for what failed, e.g. "detail pane" or "Metrics view". */
  label?: string;
  /**
   * When any value in this array changes (shallow, by index), the boundary
   * clears its error state and re-renders `children`. Callers pass a value that
   * changes when the user navigates away (e.g. the selected view), so a crashed
   * subtree recovers automatically instead of staying stuck on the fallback.
   */
  resetKeys?: ReadonlyArray<unknown>;
  /** Optional callback fired when the boundary resets (manual or via resetKeys). */
  onReset?: () => void;
  children: ReactNode;
}

interface ErrorBoundaryState {
  error: Error | null;
}

/**
 * Matches the messages browsers throw when a dynamically `import()`ed module
 * (a `lazy()` chunk) fails to load — typically a network error, or a 404 on a
 * hashed chunk that no longer exists after a redeploy. These need a hard page
 * reload (to fetch the current index + chunk manifest), not a soft retry of the
 * same stale URL.
 */
function isChunkLoadError(error: Error): boolean {
  const message = error.message || '';
  return (
    /Failed to fetch dynamically imported module/i.test(message) ||
    /Importing a module script failed/i.test(message) ||
    /error loading dynamically imported module/i.test(message)
  );
}

/**
 * Catches render-time exceptions in a subtree and shows a friendly inline panel
 * instead of unmounting the whole app. Used in two ways:
 *
 *  - Around panels that render *untrusted* captured traffic (request/response
 *    bodies parsed client-side), where a malformed body could otherwise throw
 *    and blank the entire view. Callers key the boundary on the selected item so
 *    selecting a different, well-formed row recovers without a manual retry.
 *  - Around the app's view-switching region, keyed on the active view via
 *    `resetKeys`, so a crash in one view (including a failed `lazy()` chunk
 *    import) shows the fallback rather than blanking the whole app, and is
 *    cleared automatically when the user navigates to another tab.
 *
 * For a dynamic-import/chunk-load failure the fallback offers a hard "Reload
 * page" (a stale/missing chunk can't be recovered by re-rendering); for any
 * other error it offers "Try again", which resets the boundary in place.
 */
export default class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // Surface to the console for debugging; the inline panel is the user-facing path.
    console.error(`ErrorBoundary${this.props.label ? ` (${this.props.label})` : ''} caught:`, error, info.componentStack);
  }

  componentDidUpdate(prevProps: ErrorBoundaryProps): void {
    if (this.state.error === null) {
      return;
    }
    // Clear the error when any resetKey changes — the user has navigated away
    // from (or otherwise invalidated) the crashed subtree.
    if (resetKeysChanged(prevProps.resetKeys, this.props.resetKeys)) {
      this.reset();
    }
  }

  reset = (): void => {
    this.props.onReset?.();
    this.setState({ error: null });
  };

  render(): ReactNode {
    const { error } = this.state;
    if (error) {
      const chunkError = isChunkLoadError(error);
      const what = this.props.label ?? 'this view';
      return (
        <Box sx={{ p: 3, display: 'flex', justifyContent: 'center' }}>
          <Alert
            severity="error"
            role="alert"
            icon={<ErrorOutlineIcon fontSize="inherit" />}
            sx={{ maxWidth: 560, width: '100%' }}
          >
            <AlertTitle>This view failed to load</AlertTitle>
            <Typography variant="body2" sx={{ mb: 1 }}>
              {chunkError
                ? `Could not load ${what}. This usually means the app was updated — reload the page to fetch the latest version.`
                : `Something went wrong while rendering ${what}.`}
            </Typography>
            <Typography
              variant="caption"
              component="pre"
              sx={{
                m: 0,
                mb: 1.5,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
                opacity: 0.85,
              }}
            >
              {error.message || 'An unexpected error occurred.'}
            </Typography>
            <Stack direction="row" spacing={1}>
              {chunkError ? (
                <Button
                  size="small"
                  variant="contained"
                  color="error"
                  startIcon={<RefreshIcon />}
                  onClick={() => window.location.reload()}
                >
                  Reload page
                </Button>
              ) : (
                <Button
                  size="small"
                  variant="contained"
                  color="error"
                  startIcon={<RefreshIcon />}
                  onClick={this.reset}
                >
                  Try again
                </Button>
              )}
            </Stack>
          </Alert>
        </Box>
      );
    }
    return this.props.children;
  }
}

/** Shallow, index-wise comparison of two resetKeys arrays. */
function resetKeysChanged(
  prev: ReadonlyArray<unknown> | undefined,
  next: ReadonlyArray<unknown> | undefined,
): boolean {
  if (prev === next) {
    return false;
  }
  if (prev === undefined || next === undefined) {
    return prev !== next;
  }
  if (prev.length !== next.length) {
    return true;
  }
  return prev.some((value, index) => !Object.is(value, next[index]));
}
