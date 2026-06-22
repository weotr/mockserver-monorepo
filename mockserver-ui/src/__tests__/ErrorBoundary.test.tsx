import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ErrorBoundary from '../components/ErrorBoundary';

/** A child that throws on render when `boom` is true. */
function Boom({ boom, message }: { boom: boolean; message?: string }) {
  if (boom) {
    throw new Error(message ?? 'kaboom');
  }
  return <div>healthy child</div>;
}

function withTheme(node: React.ReactNode) {
  return <ThemeProvider theme={buildTheme('dark')}>{node}</ThemeProvider>;
}

describe('ErrorBoundary', () => {
  // The boundary logs caught errors via console.error; React also logs the
  // caught error. Silence both so the suite output stays clean.
  let errorSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    errorSpy.mockRestore();
  });

  it('renders children when nothing throws', () => {
    render(withTheme(
      <ErrorBoundary label="detail pane">
        <Boom boom={false} />
      </ErrorBoundary>,
    ));
    expect(screen.getByText('healthy child')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows the fallback with the error message when a child throws', () => {
    render(withTheme(
      <ErrorBoundary label="detail pane">
        <Boom boom message="malformed body" />
      </ErrorBoundary>,
    ));
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText('This view failed to load')).toBeInTheDocument();
    expect(screen.getByText('malformed body')).toBeInTheDocument();
    // Non-chunk errors offer "Try again", not "Reload page".
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reload page' })).not.toBeInTheDocument();
  });

  it('offers "Reload page" for a dynamic-import / chunk-load failure', () => {
    render(withTheme(
      <ErrorBoundary label="Metrics view">
        <Boom boom message="Failed to fetch dynamically imported module: https://x/chunk.js" />
      </ErrorBoundary>,
    ));
    expect(screen.getByRole('button', { name: 'Reload page' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('"Try again" clears the error and re-renders a now-healthy child', async () => {
    const user = userEvent.setup();

    function Harness() {
      const [boom, setBoom] = useState(true);
      return (
        <ErrorBoundary label="detail pane" onReset={() => setBoom(false)}>
          <Boom boom={boom} />
        </ErrorBoundary>
      );
    }

    render(withTheme(<Harness />));
    expect(screen.getByRole('alert')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Try again' }));

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('healthy child')).toBeInTheDocument();
  });

  it('clears the error when a resetKey changes (navigating away)', () => {
    function Harness({ view, boom }: { view: string; boom: boolean }) {
      return (
        <ErrorBoundary label="this view" resetKeys={[view]}>
          <Boom boom={boom} />
        </ErrorBoundary>
      );
    }

    const { rerender } = render(withTheme(<Harness view="metrics" boom />));
    expect(screen.getByRole('alert')).toBeInTheDocument();

    // Switch tab (resetKey changes) and the new view's child is healthy.
    rerender(withTheme(<Harness view="dashboard" boom={false} />));

    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText('healthy child')).toBeInTheDocument();
  });

  it('does not reset while the resetKey is unchanged', () => {
    function Harness({ view, label }: { view: string; label: string }) {
      return (
        <ErrorBoundary label={label} resetKeys={[view]}>
          <Boom boom message="still broken" />
        </ErrorBoundary>
      );
    }

    const { rerender } = render(withTheme(<Harness view="metrics" label="a" />));
    expect(screen.getByRole('alert')).toBeInTheDocument();

    // Re-render with the same resetKey (only an unrelated prop changed): the
    // boundary must stay in its error state, not flap back to the throwing child.
    rerender(withTheme(<Harness view="metrics" label="b" />));
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('fires onReset when a resetKey change clears the error', () => {
    const onReset = vi.fn();

    function Harness({ view, boom }: { view: string; boom: boolean }) {
      return (
        <ErrorBoundary label="this view" resetKeys={[view]} onReset={onReset}>
          <Boom boom={boom} />
        </ErrorBoundary>
      );
    }

    const { rerender } = render(withTheme(<Harness view="metrics" boom />));
    expect(onReset).not.toHaveBeenCalled();

    rerender(withTheme(<Harness view="dashboard" boom={false} />));
    expect(onReset).toHaveBeenCalledOnce();
  });
});
