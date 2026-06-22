import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitForElementToBeRemoved } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import HumanErrorAlert from '../components/HumanErrorAlert';

function renderAlert(ui: React.ReactElement) {
  return render(<ThemeProvider theme={buildTheme('light')}>{ui}</ThemeProvider>);
}

describe('HumanErrorAlert', () => {
  it('renders the message and no Details toggle when there are no details', () => {
    renderAlert(<HumanErrorAlert message="The request was rejected as invalid." />);
    expect(screen.getByText(/rejected as invalid/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /details/i })).not.toBeInTheDocument();
  });

  it('hides raw details until the Details toggle is clicked, then hides again', async () => {
    const user = userEvent.setup();
    renderAlert(
      <HumanErrorAlert
        error={{ message: 'The request was rejected as invalid.', details: 'long Java stack trace...' }}
      />,
    );

    // Short message visible, raw details hidden initially.
    expect(screen.getByText(/rejected as invalid/i)).toBeInTheDocument();
    expect(screen.queryByText(/long Java stack trace/)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Details' }));
    expect(screen.getByText(/long Java stack trace/)).toBeInTheDocument();

    // Toggling back collapses the raw details (Collapse animates out, so wait
    // for the element to leave the DOM).
    await user.click(screen.getByRole('button', { name: 'Hide details' }));
    await waitForElementToBeRemoved(() => screen.queryByText(/long Java stack trace/));
  });

  it('prefers the error prop over discrete message/details props', () => {
    renderAlert(
      <HumanErrorAlert
        error={{ message: 'from error prop' }}
        message="from message prop"
      />,
    );
    expect(screen.getByText('from error prop')).toBeInTheDocument();
    expect(screen.queryByText('from message prop')).not.toBeInTheDocument();
  });

  it('forwards data-testid and renders a close button when onClose is given', () => {
    const onClose = vi.fn();
    renderAlert(
      <HumanErrorAlert message="boom" data-testid="my-alert" onClose={onClose} />,
    );
    expect(screen.getByTestId('my-alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /close/i })).toBeInTheDocument();
  });
});
