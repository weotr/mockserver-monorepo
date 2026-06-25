import { lazy, Suspense } from 'react';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import type { JsonDiffViewerProps } from './JsonDiffViewer';

// Code-split wrapper for the Monaco-backed JsonDiffViewer. The real component
// (and its transitive `monaco-editor` dependency plus the web-worker bundles)
// is multi-MB and was being pulled into the main dashboard chunk via the static
// import graph (TrafficInspector -> CaptureAsMockDialog -> JsonDiffViewer ->
// monaco). Importing it lazily here keeps Monaco — and the heap/workers it
// spins up — out of the initial bundle so it only loads when a diff is actually
// rendered. The props type is imported type-only so this wrapper never
// statically references monaco.
const Inner = lazy(() => import('./JsonDiffViewer'));

/**
 * Lazy, Suspense-wrapped drop-in for {@link JsonDiffViewer} with the same props.
 * Shows a sized placeholder (matching the diff height) with a centered spinner
 * while the Monaco chunk loads, so there is no layout jump.
 */
export default function JsonDiffViewerLazy(props: JsonDiffViewerProps) {
  return (
    <Suspense
      fallback={
        <Box
          sx={{
            height: props.height ?? 260,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            border: 1,
            borderColor: 'divider',
            borderRadius: 1,
          }}
        >
          <CircularProgress size={24} />
        </Box>
      }
    >
      <Inner {...props} />
    </Suspense>
  );
}
