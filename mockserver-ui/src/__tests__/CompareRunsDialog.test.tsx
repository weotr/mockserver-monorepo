import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import CompareRunsDialog from '../components/CompareRunsDialog';
import { useDashboardStore } from '../store';

// ---------------------------------------------------------------------------
// Setup
// ---------------------------------------------------------------------------

beforeEach(() => {
  vi.restoreAllMocks();
  useDashboardStore.setState({
    proxiedRequests: [],
    activeExpectations: [],
  });
});

function renderDialog(overrides: Partial<Parameters<typeof CompareRunsDialog>[0]> = {}) {
  const defaults = {
    open: true,
    onClose: vi.fn(),
    ...overrides,
  };
  return {
    ...render(
      <ThemeProvider theme={buildTheme('dark')}>
        <CompareRunsDialog {...defaults} />
      </ThemeProvider>,
    ),
    ...defaults,
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('CompareRunsDialog', () => {
  it('renders dialog title', () => {
    renderDialog();
    expect(screen.getByText('Compare Runs')).toBeInTheDocument();
  });

  it('does not render when open is false', () => {
    renderDialog({ open: false });
    expect(screen.queryByText('Compare Runs')).not.toBeInTheDocument();
  });

  it('shows empty state when no traces are selected', () => {
    renderDialog();
    expect(screen.getByText('Choose two captured traces to compare.')).toBeInTheDocument();
  });

  it('shows Trace A and Trace B selectors', () => {
    renderDialog();
    expect(screen.getByLabelText('Trace A')).toBeInTheDocument();
    expect(screen.getByLabelText('Trace B')).toBeInTheDocument();
  });

  it('shows Close button', () => {
    renderDialog();
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument();
  });

  it('calls onClose when Close button is clicked', async () => {
    const { default: userEvent } = await import('@testing-library/user-event');
    const user = userEvent.setup();
    const { onClose } = renderDialog();

    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(onClose).toHaveBeenCalledOnce();
  });
});
