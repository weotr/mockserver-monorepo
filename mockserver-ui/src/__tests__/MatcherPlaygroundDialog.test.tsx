import { describe, it, expect, afterEach } from 'vitest';
import { render, screen, cleanup, fireEvent } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import MatcherPlaygroundDialog from '../components/MatcherPlaygroundDialog';

afterEach(cleanup);

function renderDialog(initialExpectation?: string) {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <MatcherPlaygroundDialog open onClose={() => {}} initialExpectation={initialExpectation} />
    </ThemeProvider>,
  );
}

function setSample(fields: Partial<Record<'Sample method' | 'Sample path' | 'Sample query' | 'Sample headers' | 'Sample body', string>>) {
  for (const [label, value] of Object.entries(fields)) {
    const input = screen.getByLabelText(label) as HTMLInputElement | HTMLTextAreaElement;
    fireEvent.change(input, { target: { value } });
  }
}

describe('MatcherPlaygroundDialog', () => {
  it('shows WOULD MATCH for a request that satisfies the expectation', () => {
    renderDialog('{"httpRequest":{"method":"GET","path":"/api/.*"}}');
    setSample({ 'Sample method': 'GET', 'Sample path': '/api/users' });
    fireEvent.click(screen.getByRole('button', { name: 'Test request' }));
    expect(screen.getByText('WOULD MATCH')).toBeTruthy();
  });

  it('shows WOULD NOT MATCH for a request that fails the path matcher', () => {
    renderDialog('{"httpRequest":{"method":"GET","path":"/orders/.*"}}');
    setSample({ 'Sample method': 'GET', 'Sample path': '/api/users' });
    fireEvent.click(screen.getByRole('button', { name: 'Test request' }));
    expect(screen.getByText('WOULD NOT MATCH')).toBeTruthy();
    expect(screen.getByText('path')).toBeTruthy();
  });

  it('shows INCONCLUSIVE when the expectation uses an unsupported matcher field', () => {
    renderDialog('{"httpRequest":{"method":"GET","path":"/api/users","cookies":{"s":"1"}}}');
    setSample({ 'Sample method': 'GET', 'Sample path': '/api/users' });
    fireEvent.click(screen.getByRole('button', { name: 'Test request' }));
    expect(screen.getByText('INCONCLUSIVE')).toBeTruthy();
  });

  it('handles invalid expectation JSON gracefully', () => {
    renderDialog('{ not valid json');
    fireEvent.click(screen.getByRole('button', { name: 'Test request' }));
    // No verdict chip rendered; a parse error is surfaced as TextField helper text.
    expect(screen.queryByText('WOULD MATCH')).toBeNull();
    expect(screen.queryByText('WOULD NOT MATCH')).toBeNull();
    // The candidate expectation field is flagged invalid (aria-invalid set by MUI error).
    const expField = screen.getByLabelText('Candidate expectation JSON');
    expect(expField.getAttribute('aria-invalid')).toBe('true');
  });

  it('always labels the result as a browser-side preview', () => {
    renderDialog();
    expect(screen.getByText(/Preview only/i)).toBeTruthy();
  });
});
