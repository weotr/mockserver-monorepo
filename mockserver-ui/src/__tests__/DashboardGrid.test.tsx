/**
 * DashboardGrid resize-divider tests.
 *
 * The non-stacked (desktop) 2x2 grid exposes two drag dividers — a vertical
 * column splitter and a horizontal row splitter — overlaid in the grid gap. The
 * stacked (small-screen) single-column layout has no dividers.
 *
 * jsdom has no layout, so we don't assert pixel geometry; we assert the handles
 * are present/absent and carry the right accessibility roles, and that the grid
 * renders with default split fractions without any measurement.
 */
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import DashboardGrid from '../components/DashboardGrid';
import { useDashboardStore } from '../store';

const theme = buildTheme('dark');

function stubMatchMedia(matches: boolean) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia;
}

function renderGrid() {
  return render(
    <ThemeProvider theme={theme}>
      <DashboardGrid />
    </ThemeProvider>,
  );
}

beforeEach(() => {
  useDashboardStore.setState({
    recordedRequests: [],
    proxiedRequests: [],
    receivedSearch: '',
    proxiedSearch: '',
  });
});

afterEach(() => {
  cleanup();
  // @ts-expect-error allow deleting the optional stub
  delete window.matchMedia;
});

describe('DashboardGrid resize dividers', () => {
  it('renders both dividers on a desktop (non-stacked) viewport', () => {
    // jsdom default: no matchMedia → useMediaQuery false → desktop 2x2 grid.
    renderGrid();
    const col = screen.getByTestId('dashboard-col-resizer');
    const row = screen.getByTestId('dashboard-row-resizer');
    expect(col).toBeInTheDocument();
    expect(row).toBeInTheDocument();
    expect(col).toHaveAttribute('role', 'separator');
    expect(col).toHaveAttribute('aria-orientation', 'vertical');
    expect(row).toHaveAttribute('aria-orientation', 'horizontal');
    // Default split is 0.5/0.5 — the value renders without any measurement.
    expect(col).toHaveAttribute('aria-valuenow', '0.5');
    expect(row).toHaveAttribute('aria-valuenow', '0.5');
  });

  it('renders no dividers on a stacked (small-screen) viewport', () => {
    stubMatchMedia(true);
    renderGrid();
    expect(screen.queryByTestId('dashboard-col-resizer')).not.toBeInTheDocument();
    expect(screen.queryByTestId('dashboard-row-resizer')).not.toBeInTheDocument();
  });
});
