import Box from '@mui/material/Box';
import Tooltip from '@mui/material/Tooltip';
import type { SxProps, Theme } from '@mui/material/styles';
import type { ElementType } from 'react';

interface TruncatedTextProps {
  text: string;
  maxWidth?: number | string;
  sx?: SxProps<Theme>;
  component?: ElementType;
}

/**
 * Single-line text that ellipsis-truncates when it overflows, wrapped in a
 * Tooltip showing the full text so nothing is unrecoverable.
 */
export default function TruncatedText({ text, maxWidth, sx, component }: TruncatedTextProps) {
  return (
    <Tooltip title={text || ''} disableHoverListener={!text}>
      <Box
        component={component ?? 'span'}
        sx={[
          {
            display: 'block',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            minWidth: 0,
            ...(maxWidth != null ? { maxWidth } : {}),
          },
          // Array form so a caller's sx — object, theme-callback, or array — is
          // merged by MUI rather than spread (which would drop function/array forms).
          ...(Array.isArray(sx) ? sx : [sx ?? false]),
        ]}
      >
        {text}
      </Box>
    </Tooltip>
  );
}
