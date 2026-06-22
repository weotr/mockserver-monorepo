import { useState } from 'react';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import ContentCopyIcon from '@mui/icons-material/ContentCopy';
import CheckIcon from '@mui/icons-material/Check';

interface CopyButtonProps {
  text: string;
  size?: 'small' | 'medium';
}

export default function CopyButton({ text, size = 'small' }: CopyButtonProps) {
  const [status, setStatus] = useState<'idle' | 'copied' | 'failed'>('idle');

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setStatus('copied');
      setTimeout(() => setStatus('idle'), 1500);
    } catch {
      setStatus('failed');
      setTimeout(() => setStatus('idle'), 2000);
    }
  };

  const tooltipTitle = status === 'copied' ? 'Copied!' : status === 'failed' ? 'Copy failed' : 'Copy';

  return (
    <Tooltip title={tooltipTitle}>
      <IconButton
        size={size}
        onClick={handleCopy}
        sx={{
          opacity: 0.6,
          '&:hover': { opacity: 1 },
          p: '2px',
          '& .MuiSvgIcon-root': { fontSize: '0.875rem' },
        }}
      >
        {status === 'copied' ? <CheckIcon /> : <ContentCopyIcon />}
      </IconButton>
    </Tooltip>
  );
}
