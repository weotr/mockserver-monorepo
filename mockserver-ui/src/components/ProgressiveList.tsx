import { useLayoutEffect, useRef, useState, type ReactNode } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';

interface ProgressiveListProps {
  /** Number of rows. */
  count: number;
  /** Stable React key for the row at `index`. */
  getKey: (index: number) => string;
  /** Render the row at `index`. */
  renderRow: (index: number) => ReactNode;
  /** Estimated row height (px) used before a row is measured. */
  estimateSize?: number;
  /** Extra rows rendered above/below the viewport so scrolling stays smooth. */
  overscan?: number;
  /**
   * Rows rendered on the very first paint, before the scroll viewport has been
   * discovered — enough to fill the visible area cheaply without mounting the
   * whole list. Once the viewport is known the list windows instead.
   */
  initial?: number;
}

/**
 * Renders a long list with true viewport virtualization (windowing): only the
 * rows in (or near) the visible area are mounted in the DOM, regardless of how
 * many rows the list contains. A 50k-entry log therefore mounts a few dozen row
 * elements rather than 50k, so scrolling, filtering and appending stay smooth.
 *
 * The data contract is unchanged from the previous idle-batched implementation —
 * callers still pass `count`, a stable `getKey(index)` and `renderRow(index)`,
 * and ordering/selection/append semantics are driven entirely by the caller's
 * data. Only the *rendering* strategy changed: instead of progressively mounting
 * every row, the list windows them.
 *
 * Rows may have variable height (entries expand/collapse), so heights are
 * measured dynamically via `measureElement` rather than assumed fixed.
 *
 * The list scrolls inside the nearest scrollable ancestor (the panel's scroll
 * area), which it discovers on mount via a stable probe element — it does not
 * introduce its own scroll container, so the panel's auto-scroll-to-top
 * behaviour keeps working.
 *
 * Render strategy by phase:
 *  - First paint (scroll viewport not yet resolved): render only `initial` rows,
 *    so the first mount is cheap even for a 50k-entry list. A layout effect then
 *    resolves the scroll ancestor synchronously before the browser paints.
 *  - Viewport resolved with a usable height: window the rows.
 *  - No scrollable ancestor, or a zero-height viewport (non-layout/headless
 *    environment such as jsdom, or a panel laid out at 0px): render every row so
 *    the full set stays reachable when windowing cannot run.
 */
function findScrollParent(el: HTMLElement | null): HTMLElement | null {
  let node: HTMLElement | null = el?.parentElement ?? null;
  while (node) {
    const style = typeof getComputedStyle === 'function' ? getComputedStyle(node) : null;
    const overflowY = style?.overflowY;
    if (overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay') {
      return node;
    }
    node = node.parentElement;
  }
  return null;
}

export default function ProgressiveList({
  count,
  getKey,
  renderRow,
  estimateSize = 56,
  overscan = 8,
  initial = 20,
}: ProgressiveListProps) {
  // A stable, always-present probe used only to locate the scroll ancestor. It
  // is never swapped between render branches, so the ref stays attached and
  // re-discovery never races a branch switch.
  const probeRef = useRef<HTMLDivElement>(null);
  const [scrollParent, setScrollParent] = useState<HTMLElement | null>(null);

  // Discover the panel's scroll container synchronously after the first commit
  // and before paint, so the browser never paints the cheap first-render slice
  // for long lists — it goes straight to the windowed render.
  useLayoutEffect(() => {
    const parent = findScrollParent(probeRef.current);
    setScrollParent((prev) => (prev === parent ? prev : parent));
  }, []);

  const virtualizer = useVirtualizer({
    count,
    getScrollElement: () => scrollParent,
    estimateSize: () => estimateSize,
    overscan,
    getItemKey: (index) => getKey(index),
  });

  // The probe sits at the top of the list in every branch so findScrollParent
  // always has a stable anchor into the panel's DOM.
  const probe = <div ref={probeRef} style={{ height: 0 }} aria-hidden />;

  const viewportHeight = scrollParent ? scrollParent.offsetHeight : 0;

  // Windowing is possible only once a scrollable ancestor with a real height is
  // known. Until then (first paint) render a cheap bounded slice; if no usable
  // viewport ever resolves (headless / 0px), render the full list.
  if (!scrollParent || viewportHeight === 0) {
    const rendered = scrollParent ? count : Math.min(initial, count);
    return (
      <>
        {probe}
        {Array.from({ length: rendered }, (_, i) => (
          <div key={getKey(i)} data-vrow={i}>
            {renderRow(i)}
          </div>
        ))}
      </>
    );
  }

  const items = virtualizer.getVirtualItems();

  return (
    <>
      {probe}
      <div style={{ height: virtualizer.getTotalSize(), width: '100%', position: 'relative' }}>
        {items.map((item) => (
          <div
            key={item.key as string}
            data-index={item.index}
            data-vrow={item.index}
            ref={virtualizer.measureElement}
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              width: '100%',
              transform: `translateY(${item.start}px)`,
            }}
          >
            {renderRow(item.index)}
          </div>
        ))}
      </div>
    </>
  );
}
