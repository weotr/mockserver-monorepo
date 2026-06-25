import { lazy, Suspense } from 'react';
import Box from '@mui/material/Box';
import CircularProgress from '@mui/material/CircularProgress';
import type { JsonEditorProps } from './JsonEditor';

// Code-split wrapper for the Monaco-backed JsonEditor. The real component (and
// its transitive `monaco-editor` dependency plus the web-worker bundles) is
// multi-MB and was being pulled into the main dashboard chunk via the static
// import graph. Importing it lazily here keeps Monaco — and the heap/workers it
// spins up — out of the initial bundle so it only loads when an editor is
// actually rendered. The props type is imported type-only so this wrapper never
// statically references monaco.
const Inner = lazy(() => import('./JsonEditor'));

/**
 * Lazy, Suspense-wrapped drop-in for {@link JsonEditor} with the same props.
 * Shows a sized placeholder (matching the editor height) with a centered
 * spinner while the Monaco chunk loads, so there is no layout jump.
 */
export default function JsonEditorLazy(props: JsonEditorProps) {
  return (
    <Suspense
      fallback={
        <Box
          sx={{
            height: props.height ?? 180,
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
