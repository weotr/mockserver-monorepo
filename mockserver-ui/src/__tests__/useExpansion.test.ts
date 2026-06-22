import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useExpansion } from '../hooks/useExpansion';

describe('useExpansion', () => {
  it('starts with every key collapsed', () => {
    const { result } = renderHook(() => useExpansion());
    expect(result.current.isExpanded('a')).toBe(false);
    expect(result.current.isExpanded('anything-else')).toBe(false);
  });

  it('toggling a key expands it, toggling again collapses it', () => {
    const { result } = renderHook(() => useExpansion());

    act(() => result.current.toggle('row-1'));
    expect(result.current.isExpanded('row-1')).toBe(true);

    act(() => result.current.toggle('row-1'));
    expect(result.current.isExpanded('row-1')).toBe(false);
  });

  it('tracks each key independently', () => {
    const { result } = renderHook(() => useExpansion());

    act(() => result.current.toggle('row-1'));
    act(() => result.current.toggle('row-2'));
    expect(result.current.isExpanded('row-1')).toBe(true);
    expect(result.current.isExpanded('row-2')).toBe(true);

    // Collapsing one leaves the other expanded.
    act(() => result.current.toggle('row-1'));
    expect(result.current.isExpanded('row-1')).toBe(false);
    expect(result.current.isExpanded('row-2')).toBe(true);
  });

  it('keeps expansion across unrelated toggles (simulating a virtualized unmount/remount window)', () => {
    const { result } = renderHook(() => useExpansion());

    // Expand a row, then drive a series of unrelated toggles — the original
    // row's expanded state must survive because it lives above the rows.
    act(() => result.current.toggle('kept'));
    act(() => result.current.toggle('other-a'));
    act(() => result.current.toggle('other-b'));
    act(() => result.current.toggle('other-a')); // collapse other-a again

    expect(result.current.isExpanded('kept')).toBe(true);
    expect(result.current.isExpanded('other-a')).toBe(false);
    expect(result.current.isExpanded('other-b')).toBe(true);
  });

  it('exposes referentially stable callbacks so memoized rows are not defeated', () => {
    const { result, rerender } = renderHook(() => useExpansion());
    const firstToggle = result.current.toggle;

    // A re-render with no state change must not change the toggle identity.
    rerender();
    expect(result.current.toggle).toBe(firstToggle);

    // After a state change the toggle identity is still stable (only isExpanded,
    // which closes over the set, changes), so it remains safe to pass to memo rows.
    act(() => result.current.toggle('x'));
    expect(result.current.toggle).toBe(firstToggle);
  });
});
