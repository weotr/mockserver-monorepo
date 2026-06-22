import { useId } from 'react';
import { DiffEditor, loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';
import { monospaceFontFamily } from '../theme';

// Reuse the same locally-bundled Monaco the JsonEditor wires up, so the diff
// view never reaches for an external CDN at runtime (the dashboard is served
// from the — often air-gapped — mock server itself). Calling loader.config here
// as well as in JsonEditor is harmless: it just points the loader at the
// bundled instance, which is idempotent.
loader.config({ monaco });

export interface JsonDiffViewerProps {
  /** The "before" document (e.g. the existing expectation as stored). */
  original: string;
  /** The "after" document (e.g. the JSON that will be PUT). */
  modified: string;
  /** Monaco language id — defaults to `json`. */
  language?: string;
  /** Accessible label / field title rendered above the diff. */
  label?: string;
  ariaLabel?: string;
  /** Diff height in px. */
  height?: number;
  /**
   * When true (the default) the original pane sits to the left of the modified
   * pane (side-by-side). When false the diff is rendered inline.
   */
  sideBySide?: boolean;
}

/**
 * A reusable Monaco-backed read-only DIFF view that shows a clear before→after
 * comparison of two JSON documents. Used before creating a mock (capture flow)
 * and before updating an existing expectation (Composer) so the user sees
 * exactly what will change before they submit.
 */
export default function JsonDiffViewer({
  original,
  modified,
  language = 'json',
  label,
  ariaLabel,
  height = 260,
  sideBySide = true,
}: JsonDiffViewerProps) {
  const theme = useTheme();
  const summaryId = useId();
  const slug = summaryId.replace(/[^a-zA-Z0-9]/g, '');
  const isDark = theme.palette.mode === 'dark';

  return (
    <Box>
      {label && (
        <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mb: 0.5 }}>
          {label}
        </Typography>
      )}
      <Box
        sx={{
          border: 1,
          borderColor: 'divider',
          borderRadius: 1,
          overflow: 'hidden',
        }}
        data-testid="json-diff-viewer"
        aria-label={ariaLabel ?? label}
      >
        <DiffEditor
          height={height}
          language={language}
          theme={isDark ? 'vs-dark' : 'vs'}
          original={original}
          modified={modified}
          originalModelPath={`inmemory://mockserver/${slug}-original.json`}
          modifiedModelPath={`inmemory://mockserver/${slug}-modified.json`}
          options={{
            readOnly: true,
            renderSideBySide: sideBySide,
            fontFamily: monospaceFontFamily,
            fontSize: 12.5,
            minimap: { enabled: false },
            lineNumbers: 'on',
            scrollBeyondLastLine: false,
            wordWrap: 'on',
            automaticLayout: true,
            renderLineHighlight: 'none',
            scrollbar: { alwaysConsumeMouseWheel: false },
          }}
        />
      </Box>
    </Box>
  );
}
