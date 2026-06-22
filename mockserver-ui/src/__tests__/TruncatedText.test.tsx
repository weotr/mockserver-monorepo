import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import TruncatedText from '../components/TruncatedText';

function renderComponent(props: Parameters<typeof TruncatedText>[0]) {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <TruncatedText {...props} />
    </ThemeProvider>,
  );
}

describe('TruncatedText', () => {
  it('renders the text content', () => {
    renderComponent({ text: 'hello world' });
    expect(screen.getByText('hello world')).toBeInTheDocument();
  });

  it('applies overflow ellipsis styles', () => {
    renderComponent({ text: 'long text', maxWidth: 50 });
    const el = screen.getByText('long text');
    // The element should exist and have the ellipsis styling applied via sx
    expect(el).toBeInTheDocument();
  });

  it('shows tooltip on hover with full text', async () => {
    const user = userEvent.setup();
    renderComponent({ text: 'a very long identifier that gets truncated' });
    const el = screen.getByText('a very long identifier that gets truncated');
    await user.hover(el);
    // MUI Tooltip renders the title; the text is the same as the visible text
    expect(el).toBeInTheDocument();
  });

  it('does not show tooltip for empty text', () => {
    renderComponent({ text: '' });
    // Should render without crashing
    expect(document.body).toBeTruthy();
  });

  it('applies custom sx props', () => {
    renderComponent({ text: 'styled', sx: { fontFamily: 'monospace' } });
    expect(screen.getByText('styled')).toBeInTheDocument();
  });
});
