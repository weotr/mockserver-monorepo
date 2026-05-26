import { useMemo } from 'react';
import Box from '@mui/material/Box';
import Tabs from '@mui/material/Tabs';
import Tab from '@mui/material/Tab';
import { useState } from 'react';
import type { ConversationDraft } from '../lib/conversationCodegen';
import {
  conversationToJava,
  conversationToJson,
  conversationToMcpCall,
} from '../lib/conversationCodegen';
import CopyButton from './CopyButton';

// ---------------------------------------------------------------------------
// Props
// ---------------------------------------------------------------------------

interface Step3Props {
  draft: ConversationDraft;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function ConversationWizardStep3({ draft }: Step3Props) {
  const [tab, setTab] = useState(0);

  const javaCode = useMemo(() => conversationToJava(draft), [draft]);
  const jsonCode = useMemo(() => conversationToJson(draft), [draft]);
  const mcpCode = useMemo(() => conversationToMcpCall(draft), [draft]);

  const tabLabels = ['Java', 'JSON', 'MCP'];
  const outputs = [javaCode, jsonCode, mcpCode];
  const safeTab = Math.min(tab, tabLabels.length - 1);

  return (
    <Box sx={{ py: 1 }}>
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
        <Box sx={{ position: 'absolute', top: 4, right: 4 }}>
          <CopyButton text={outputs[safeTab]!} />
        </Box>
        <Box
          component="pre"
          sx={{
            fontFamily: 'monospace',
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
