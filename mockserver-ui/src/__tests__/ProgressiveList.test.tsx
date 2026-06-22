import { describe, it, expect, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import ProgressiveList from '../components/ProgressiveList';

function vrows() {
  return screen.queryAllByTestId('row');
}

/**
 * Renders ProgressiveList inside a real scrollable ancestor with a fixed
 * viewport height, so the virtualizer windows (instead of falling back to
 * rendering every row). jsdom reports 0 for layout-derived sizes, so we mock
 * the scroll container's clientHeight and the measured row height.
 */
function renderWindowed(count: number, opts?: { viewport?: number; rowHeight?: number }) {
  const viewport = opts?.viewport ?? 400;
  const rowHeight = opts?.rowHeight ?? 40;

  const Row = (i: number): ReactNode => (
    <div data-testid="row" style={{ height: rowHeight }}>
      row {i}
    </div>
  );

  // A scrollable wrapper: overflow-y:auto makes findScrollParent pick it up.
  // @tanstack/virtual-core measures via offsetWidth/offsetHeight (0 in jsdom),
  // so mock those: the scroll container reports the viewport height, every row
  // reports rowHeight. Prototypes are restored in afterEach.
  // virtual-core derives the visible-window count from getRect (offsetHeight)
  // and clamps scroll bounds with clientHeight, so mock both. The scroll
  // container reports the viewport height; every row reports rowHeight.
  for (const prop of ['offsetHeight', 'clientHeight'] as const) {
    Object.defineProperty(HTMLElement.prototype, prop, {
      configurable: true,
      get(): number {
        return (this as HTMLElement).style.overflowY === 'auto' ? viewport : rowHeight;
      },
    });
  }
  for (const prop of ['offsetWidth', 'clientWidth'] as const) {
    Object.defineProperty(HTMLElement.prototype, prop, {
      configurable: true,
      get(): number {
        return 600;
      },
    });
  }

  return render(
    <div style={{ overflowY: 'auto', height: viewport }}>
      <ProgressiveList count={count} getKey={(i) => `r${i}`} renderRow={Row} estimateSize={rowHeight} overscan={4} />
    </div>,
  );
}

afterEach(() => {
  // Drop the per-test prototype overrides so other suites see clean jsdom.
  for (const prop of ['offsetHeight', 'clientHeight', 'offsetWidth', 'clientWidth']) {
    delete (HTMLElement.prototype as unknown as Record<string, unknown>)[prop];
  }
});

describe('ProgressiveList', () => {
  it('renders every row when there is no scrollable viewport (jsdom fallback)', () => {
    render(
      <ProgressiveList
        count={3}
        getKey={(i) => `r${i}`}
        renderRow={(i) => <div data-testid="row">row {i}</div>}
      />,
    );
    expect(vrows()).toHaveLength(3);
    expect(screen.getByText('row 0')).toBeInTheDocument();
  });

  it('windows a large list: only a bounded number of rows mount for 50k entries', () => {
    renderWindowed(50_000, { viewport: 400, rowHeight: 40 });

    const mounted = vrows();
    // Viewport fits ~10 rows; with overscan the virtualizer mounts a few dozen
    // at most — nowhere near 50k. The exact number depends on overscan, so we
    // assert a generous upper bound that still proves windowing.
    expect(mounted.length).toBeGreaterThan(0);
    expect(mounted.length).toBeLessThan(100);

    // The first rows are present; rows deep in the list are NOT mounted.
    expect(screen.getByText('row 0')).toBeInTheDocument();
    expect(screen.queryByText('row 49999')).not.toBeInTheDocument();
    expect(screen.queryByText('row 25000')).not.toBeInTheDocument();
  });

  it('mounts a similarly bounded window regardless of total size', () => {
    const { unmount } = renderWindowed(200, { viewport: 400, rowHeight: 40 });
    const small = vrows().length;
    unmount();

    renderWindowed(50_000, { viewport: 400, rowHeight: 40 });
    const large = vrows().length;

    // The window size is driven by the viewport, not the dataset size: a 250x
    // larger dataset must not mount proportionally more rows.
    expect(large).toBeLessThan(small * 3);
  });
});
