import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import type { KeyboardEvent as ReactKeyboardEvent, PointerEvent as ReactPointerEvent } from 'react';
import { useDragResize } from '../hooks/useDragResize';

function makeStorage(): Storage {
  const map = new Map<string, string>();
  return {
    getItem: (k: string) => (map.has(k) ? (map.get(k) as string) : null),
    setItem: (k: string, v: string) => void map.set(k, v),
    removeItem: (k: string) => void map.delete(k),
    clear: () => map.clear(),
    key: (i: number) => Array.from(map.keys())[i] ?? null,
    get length() {
      return map.size;
    },
  } as Storage;
}

describe('useDragResize', () => {
  let storage: Storage;

  beforeEach(() => {
    storage = makeStorage();
    vi.stubGlobal('localStorage', storage);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('starts at the initial value when nothing is persisted', () => {
    const { result } = renderHook(() =>
      useDragResize({
        orientation: 'vertical',
        initial: 0.5,
        min: 0.15,
        max: 0.85,
        computeFromPointer: () => 0.5,
      }),
    );
    expect(result.current.value).toBe(0.5);
    expect(result.current.dragging).toBe(false);
  });

  it('reads an initial value from localStorage when present', () => {
    storage.setItem('split', '0.7');
    const { result } = renderHook(() =>
      useDragResize({
        orientation: 'vertical',
        initial: 0.5,
        min: 0.15,
        max: 0.85,
        storageKey: 'split',
        computeFromPointer: () => 0.5,
      }),
    );
    expect(result.current.value).toBe(0.7);
  });

  it('clamps a persisted value that is out of bounds', () => {
    storage.setItem('split', '0.99');
    const { result } = renderHook(() =>
      useDragResize({
        orientation: 'vertical',
        initial: 0.5,
        min: 0.15,
        max: 0.85,
        storageKey: 'split',
        computeFromPointer: () => 0.5,
      }),
    );
    expect(result.current.value).toBe(0.85);
  });

  it('clamps setValue to [min, max] and persists', () => {
    const { result } = renderHook(() =>
      useDragResize({
        orientation: 'vertical',
        initial: 0.5,
        min: 0.15,
        max: 0.85,
        storageKey: 'split',
        computeFromPointer: () => 0.5,
      }),
    );
    act(() => result.current.setValue(2));
    expect(result.current.value).toBe(0.85);
    expect(storage.getItem('split')).toBe('0.85');

    act(() => result.current.setValue(-1));
    expect(result.current.value).toBe(0.15);
    expect(storage.getItem('split')).toBe('0.15');
  });

  it('does not throw and still works when localStorage is unavailable', () => {
    vi.stubGlobal('localStorage', undefined);
    const { result } = renderHook(() =>
      useDragResize({
        orientation: 'vertical',
        initial: 0.4,
        min: 0,
        max: 1,
        storageKey: 'split',
        computeFromPointer: () => 0.5,
      }),
    );
    expect(result.current.value).toBe(0.4);
    expect(() => act(() => result.current.setValue(0.6))).not.toThrow();
    expect(result.current.value).toBe(0.6);
  });

  it('nudges with Arrow keys on the relevant axis (vertical → Left/Right)', () => {
    const { result } = renderHook(() =>
      useDragResize({
        orientation: 'vertical',
        initial: 0.5,
        min: 0,
        max: 1,
        step: 0.1,
        computeFromPointer: () => 0.5,
      }),
    );
    const props = result.current.getHandleProps();
    expect(props.role).toBe('separator');
    expect(props['aria-orientation']).toBe('vertical');
    expect(props.tabIndex).toBe(0);

    const fakeKey = (key: string) =>
      ({ key, preventDefault: vi.fn() }) as unknown as ReactKeyboardEvent;

    act(() => result.current.getHandleProps().onKeyDown(fakeKey('ArrowRight')));
    expect(result.current.value).toBeCloseTo(0.6, 5);
    act(() => result.current.getHandleProps().onKeyDown(fakeKey('ArrowLeft')));
    expect(result.current.value).toBeCloseTo(0.5, 5);

    // Up/Down should be ignored for a vertical (width) handle.
    act(() => result.current.getHandleProps().onKeyDown(fakeKey('ArrowUp')));
    expect(result.current.value).toBeCloseTo(0.5, 5);
  });

  it('nudges with Arrow Up/Down for a horizontal (height) handle, and Home/End jump to bounds', () => {
    const { result } = renderHook(() =>
      useDragResize({
        orientation: 'horizontal',
        initial: 0.5,
        min: 0.2,
        max: 0.8,
        step: 0.05,
        computeFromPointer: () => 0.5,
      }),
    );
    const fakeKey = (key: string) =>
      ({ key, preventDefault: vi.fn() }) as unknown as ReactKeyboardEvent;

    expect(result.current.getHandleProps()['aria-orientation']).toBe('horizontal');

    act(() => result.current.getHandleProps().onKeyDown(fakeKey('ArrowDown')));
    expect(result.current.value).toBeCloseTo(0.55, 5);
    act(() => result.current.getHandleProps().onKeyDown(fakeKey('ArrowUp')));
    expect(result.current.value).toBeCloseTo(0.5, 5);

    // Left/Right ignored on a horizontal handle.
    act(() => result.current.getHandleProps().onKeyDown(fakeKey('ArrowLeft')));
    expect(result.current.value).toBeCloseTo(0.5, 5);

    act(() => result.current.getHandleProps().onKeyDown(fakeKey('Home')));
    expect(result.current.value).toBe(0.2);
    act(() => result.current.getHandleProps().onKeyDown(fakeKey('End')));
    expect(result.current.value).toBe(0.8);
  });

  it('computes the value from pointer position via the supplied callback (fraction math)', () => {
    // Simulate a container 400px wide starting at x=100; a pointer at x=300 is
    // (300-100)/400 = 0.5 of the way across.
    const compute = (event: PointerEvent) => (event.clientX - 100) / 400;
    const { result } = renderHook(() =>
      useDragResize({
        orientation: 'vertical',
        initial: 0.5,
        min: 0.15,
        max: 0.85,
        computeFromPointer: compute,
      }),
    );

    // Drive a pointer drag through the real handlers.
    const listeners = new Map<string, (e: Event) => void>();
    const target = {
      setPointerCapture: vi.fn(),
      releasePointerCapture: vi.fn(),
      addEventListener: (type: string, fn: (e: Event) => void) => {
        listeners.set(type, fn);
      },
      removeEventListener: vi.fn(),
    };
    const fire = (type: string, e: Partial<PointerEvent>) => {
      const fn = listeners.get(type);
      if (!fn) throw new Error(`no listener registered for ${type}`);
      fn(e as Event);
    };
    const down = {
      button: 0,
      pointerId: 1,
      pointerType: 'mouse',
      currentTarget: target,
      preventDefault: vi.fn(),
    } as unknown as ReactPointerEvent;

    act(() => result.current.getHandleProps().onPointerDown(down));
    expect(result.current.dragging).toBe(true);
    expect(target.setPointerCapture).toHaveBeenCalledWith(1);

    // Pointer at 300 → fraction 0.5
    act(() => fire('pointermove', { clientX: 300 }));
    expect(result.current.value).toBeCloseTo(0.5, 5);

    // Pointer dragged far right (x=500 → 1.0) clamps to max 0.85.
    act(() => fire('pointermove', { clientX: 500 }));
    expect(result.current.value).toBeCloseTo(0.85, 5);

    // Pointer up ends the drag.
    act(() => fire('pointerup', {}));
    expect(result.current.dragging).toBe(false);
  });
});
