import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ConfirmDialog from '../components/ConfirmDialog';

function renderDialog(overrides: Partial<Parameters<typeof ConfirmDialog>[0]> = {}) {
  const props = {
    open: true,
    title: 'Reset the entire server?',
    message: 'This cannot be undone.',
    confirmLabel: 'Reset server',
    onConfirm: vi.fn(),
    onClose: vi.fn(),
    ...overrides,
  };
  render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ConfirmDialog {...props} />
    </ThemeProvider>,
  );
  return props;
}

describe('ConfirmDialog', () => {
  it('renders the title and message when open', () => {
    renderDialog();
    expect(screen.getByText('Reset the entire server?')).toBeInTheDocument();
    expect(screen.getByText('This cannot be undone.')).toBeInTheDocument();
  });

  it('confirming fires onConfirm then onClose', async () => {
    const user = userEvent.setup();
    const props = renderDialog();
    await user.click(screen.getByRole('button', { name: 'Reset server' }));
    expect(props.onConfirm).toHaveBeenCalledOnce();
    expect(props.onClose).toHaveBeenCalledOnce();
  });

  it('cancelling fires onClose only, never onConfirm', async () => {
    const user = userEvent.setup();
    const props = renderDialog();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(props.onClose).toHaveBeenCalledOnce();
    expect(props.onConfirm).not.toHaveBeenCalled();
  });
});
