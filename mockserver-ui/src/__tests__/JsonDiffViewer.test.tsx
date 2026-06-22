import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import JsonDiffViewer from '../components/JsonDiffViewer';

function renderViewer(props: Partial<React.ComponentProps<typeof JsonDiffViewer>> = {}) {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <JsonDiffViewer original="{}" modified={'{\n  "a": 1\n}'} {...props} />
    </ThemeProvider>,
  );
}

describe('JsonDiffViewer', () => {
  it('renders both original and modified panes', () => {
    renderViewer();
    expect(screen.getByTestId('json-diff-viewer')).toBeInTheDocument();
    expect(screen.getByTestId('monaco-diff-original')).toHaveValue('{}');
    expect(screen.getByTestId('monaco-diff-modified')).toHaveValue('{\n  "a": 1\n}');
  });

  it('renders an accessible label when provided', () => {
    renderViewer({ label: 'Changes', ariaLabel: 'Changes preview' });
    expect(screen.getByText('Changes')).toBeInTheDocument();
    expect(screen.getByLabelText('Changes preview')).toBeInTheDocument();
  });
});
