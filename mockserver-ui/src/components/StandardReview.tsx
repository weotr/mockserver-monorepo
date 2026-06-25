import { useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import {
  standardToJava,
  standardToJson,
  standardToCurl,
  standardToNode,
  standardToPython,
  standardToGo,
  standardToCsharp,
  standardToRuby,
  standardToRust,
  type StandardActionPayload,
  type StandardMatcher,
} from '../lib/standardCodegen';
import CopyButton from './CopyButton';
import JsonDiffViewer from './JsonDiffViewerLazy';
import { monospaceFontFamily } from '../theme';

export interface StandardReviewProps {
  matcher: StandardMatcher;
  action: StandardActionPayload;
  baseUrl: string;
  /**
   * When editing an existing expectation, the JSON of the loaded expectation as
   * it currently exists on the server. When supplied, a before→after diff is
   * shown so the user sees exactly what their edits will change before the PUT.
   */
  originalJson?: string;
}

/**
 * Client-library previews (Java, Node, Python, Go, C#, Ruby, Rust) followed by
 * JSON and curl for the standard-expectation Composer flow. Mirrors the wizard's
 * Step 3 review so both kinds give the user copy-pasteable code before they
 * register on the server.
 */
export default function StandardReview({ matcher, action, baseUrl, originalJson }: StandardReviewProps) {
  const [tab, setTab] = useState(0);

  const javaCode = useMemo(() => standardToJava(matcher, action), [matcher, action]);
  const jsonCode = useMemo(() => standardToJson(matcher, action), [matcher, action]);
  const curlCode = useMemo(() => standardToCurl(matcher, action, baseUrl), [matcher, action, baseUrl]);
  const nodeCode = useMemo(() => standardToNode(matcher, action, baseUrl), [matcher, action, baseUrl]);
  const pythonCode = useMemo(() => standardToPython(matcher, action, baseUrl), [matcher, action, baseUrl]);
  const goCode = useMemo(() => standardToGo(matcher, action, baseUrl), [matcher, action, baseUrl]);
  const csharpCode = useMemo(() => standardToCsharp(matcher, action, baseUrl), [matcher, action, baseUrl]);
  const rubyCode = useMemo(() => standardToRuby(matcher, action, baseUrl), [matcher, action, baseUrl]);
  const rustCode = useMemo(() => standardToRust(matcher, action, baseUrl), [matcher, action, baseUrl]);

  // Client-library tabs first, then JSON and curl last.
  const tabLabels = ['Java', 'Node.js', 'Python', 'Go', 'C#', 'Ruby', 'Rust', 'JSON', 'curl'];
  const outputs = [javaCode, nodeCode, pythonCode, goCode, csharpCode, rubyCode, rustCode, jsonCode, curlCode];
  const safeTab = Math.min(tab, tabLabels.length - 1);

  return (
    <Box sx={{ py: 1 }}>
      {originalJson !== undefined && (
        <Box sx={{ mb: 2 }} data-testid="standard-review-diff">
          <JsonDiffViewer
            label="Changes vs the existing expectation (left = current, right = after update)"
            ariaLabel="Changes vs the existing expectation"
            original={originalJson}
            modified={jsonCode}
            height={280}
          />
        </Box>
      )}
      <Tabs
        value={safeTab}
        onChange={(_, v: number) => setTab(v)}
        sx={{ mb: 1, minHeight: 32, '& .MuiTab-root': { minHeight: 32, py: 0.5, fontSize: '0.8rem' } }}
      >
        {tabLabels.map((label) => (
          <Tab key={label} label={label} />
        ))}
      </Tabs>

      <Box sx={{ position: 'relative' }}>
        <Box sx={{ position: 'absolute', top: 4, right: 4, zIndex: 1 }}>
          <CopyButton text={outputs[safeTab]!} />
        </Box>
        <Box
          component="pre"
          sx={{
            fontFamily: monospaceFontFamily,
            fontSize: '0.75rem',
            whiteSpace: 'pre-wrap',
            wordBreak: 'break-all',
            m: 0,
            p: 1.5,
            bgcolor: 'action.hover',
            borderRadius: 1,
            overflow: 'auto',
            maxHeight: 500,
          }}
        >
          {outputs[safeTab]}
        </Box>
      </Box>
    </Box>
  );
}
