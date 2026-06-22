import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type KeyboardEvent,
  type PointerEvent as ReactPointerEvent,
} from 'react';

/**
 * Orientation of the divider handle.
 *
 * - `'vertical'`  — a vertical bar the user drags left/right to change a WIDTH
 *                   (the column split). Arrow Left/Right nudge it.
 * - `'horizontal'`— a horizontal bar the user drags up/down to change a HEIGHT
 *                   (the row split). Arrow Up/Down nudge it.
 */
export type DragResizeOrientation = 'vertical' | 'horizontal';

export interface UseDragResizeOptions {
  /** Orientation of the divider — decides which axis and which Arrow keys apply. */
  orientation: DragResizeOrientation;
  /** Initial value used when nothing is persisted (or persistence is disabled). */
  initial: number;
  /** Lower clamp bound (inclusive). */
  min: number;
  /** Upper clamp bound (inclusive). */
  max: number;
  /** Keyboard nudge step (in the same unit as `initial`/`min`/`max`). */
  step?: number;
  /**
   * Optional `localStorage` key. When set, the initial value is read from
   * storage (falling back to `initial`) and every change is written back.
   * Storage access is guarded so it degrades gracefully where unavailable.
   */
  storageKey?: string;
  /**
   * Maps a pointer event to the next raw value. The consumer owns the unit
   * decision here (fractions 0..1 for the dashboard grid, pixels for the
   * traffic master width) by reading whatever geometry it needs — typically a
   * container `getBoundingClientRect()`. The returned value is clamped to
   * `[min, max]` by the hook before it becomes the new value.
   */
  computeFromPointer: (event: PointerEvent) => number;
  /** Optional human label exposed via `aria-label` on the handle. */
  ariaLabel?: string;
}

export interface DragResizeHandleProps {
  role: 'separator';
  'aria-orientation': DragResizeOrientation;
  'aria-valuenow': number;
  'aria-valuemin': number;
  'aria-valuemax': number;
  'aria-label'?: string;
  tabIndex: 0;
  onPointerDown: (event: ReactPointerEvent) => void;
  onKeyDown: (event: KeyboardEvent) => void;
  style: CSSProperties;
}

export interface UseDragResize {
  /** Current (clamped) value. */
  value: number;
  /** Imperatively set the value (clamped + persisted). */
  setValue: (next: number) => void;
  /** True while a pointer drag is in progress — disable CSS transitions on the resized element. */
  dragging: boolean;
  /** Spread onto the divider element to make it draggable + keyboard-operable + accessible. */
  getHandleProps: () => DragResizeHandleProps;
}

function clamp(value: number, min: number, max: number): number {
  if (Number.isNaN(value)) return min;
  return Math.min(max, Math.max(min, value));
}

function readPersisted(storageKey: string | undefined, fallback: number): number {
  if (!storageKey) return fallback;
  try {
    const raw = globalThis.localStorage?.getItem(storageKey);
    if (raw == null) return fallback;
    const parsed = Number.parseFloat(raw);
    return Number.isFinite(parsed) ? parsed : fallback;
  } catch {
    // localStorage may be unavailable (private mode / SSR / tests) — ignore.
    return fallback;
  }
}

function writePersisted(storageKey: string | undefined, value: number): void {
  if (!storageKey) return;
  try {
    globalThis.localStorage?.setItem(storageKey, String(value));
  } catch {
    // Best-effort persistence; ignore quota / unavailability.
  }
}

/**
 * useDragResize — a small pointer-events-based resize primitive shared by the
 * dashboard grid (fractional column/row splits) and the traffic master/detail
 * split (pixel width).
 *
 * The hook holds a single numeric `value` clamped to `[min, max]`. It is
 * unit-agnostic: the consumer supplies `computeFromPointer` to translate a
 * pointer position into the next value (e.g. `(clientX - rect.left) / rect.width`
 * for a fraction, or raw pixels for a width), so the same hook drives both
 * fractions and pixels.
 *
 * Interaction:
 * - Pointer drag: `onPointerDown` captures the pointer (`setPointerCapture`) and
 *   listens for `pointermove`/`pointerup` on the captured element, so the drag
 *   keeps tracking even if the pointer leaves the handle. Works with mouse,
 *   touch and pen.
 * - Keyboard: the handle is a focusable `role="separator"`; Arrow keys nudge by
 *   `step` along the relevant axis, Home/End jump to `min`/`max`.
 *
 * While dragging, `dragging` is `true` — spread/condition your element's CSS
 * `transition` off it so the element tracks the pointer 1:1.
 */
export function useDragResize(options: UseDragResizeOptions): UseDragResize {
  const {
    orientation,
    initial,
    min,
    max,
    step = 0.02,
    storageKey,
    computeFromPointer,
    ariaLabel,
  } = options;

  const [value, setValueState] = useState<number>(() =>
    clamp(readPersisted(storageKey, initial), min, max),
  );
  const [dragging, setDragging] = useState(false);

  // Keep the latest compute fn / bounds in refs so the pointermove listener
  // (attached once per drag) always sees current values without re-binding.
  // Synced in an effect rather than during render (refs must not be written
  // while rendering).
  const computeRef = useRef(computeFromPointer);
  const boundsRef = useRef({ min, max });
  useEffect(() => {
    computeRef.current = computeFromPointer;
    boundsRef.current = { min, max };
  });

  const apply = useCallback(
    (next: number) => {
      const clamped = clamp(next, boundsRef.current.min, boundsRef.current.max);
      setValueState(clamped);
      writePersisted(storageKey, clamped);
      return clamped;
    },
    [storageKey],
  );

  // Derive the value we hand back already clamped to the *current* bounds, so a
  // bounds change (e.g. the container shrinks) never returns an out-of-range
  // value without needing a setState-in-effect re-clamp.
  const clampedValue = clamp(value, min, max);

  const onPointerDown = useCallback(
    (event: ReactPointerEvent) => {
      // Ignore secondary buttons; only drive the drag with the primary pointer.
      if (event.button !== 0 && event.pointerType === 'mouse') return;
      event.preventDefault();
      const target = event.currentTarget;
      try {
        target.setPointerCapture(event.pointerId);
      } catch {
        // setPointerCapture can throw in jsdom / detached nodes — drag still works.
      }
      setDragging(true);

      const handleMove = (moveEvent: Event) => {
        apply(computeRef.current(moveEvent as PointerEvent));
      };
      const handleUp = () => {
        setDragging(false);
        target.removeEventListener('pointermove', handleMove);
        target.removeEventListener('pointerup', handleUp);
        target.removeEventListener('pointercancel', handleUp);
        try {
          target.releasePointerCapture(event.pointerId);
        } catch {
          /* noop */
        }
      };

      target.addEventListener('pointermove', handleMove);
      target.addEventListener('pointerup', handleUp);
      target.addEventListener('pointercancel', handleUp);
    },
    [apply],
  );

  const onKeyDown = useCallback(
    (event: KeyboardEvent) => {
      const decKey = orientation === 'vertical' ? 'ArrowLeft' : 'ArrowUp';
      const incKey = orientation === 'vertical' ? 'ArrowRight' : 'ArrowDown';
      if (event.key === decKey) {
        event.preventDefault();
        apply(clampedValue - step);
      } else if (event.key === incKey) {
        event.preventDefault();
        apply(clampedValue + step);
      } else if (event.key === 'Home') {
        event.preventDefault();
        apply(boundsRef.current.min);
      } else if (event.key === 'End') {
        event.preventDefault();
        apply(boundsRef.current.max);
      }
    },
    [orientation, step, apply, clampedValue],
  );

  const getHandleProps = useCallback(
    (): DragResizeHandleProps => ({
      role: 'separator',
      'aria-orientation': orientation,
      'aria-valuenow': clampedValue,
      'aria-valuemin': min,
      'aria-valuemax': max,
      ...(ariaLabel ? { 'aria-label': ariaLabel } : {}),
      tabIndex: 0,
      onPointerDown,
      onKeyDown,
      style: {
        touchAction: 'none',
        cursor: orientation === 'vertical' ? 'col-resize' : 'row-resize',
      },
    }),
    [orientation, clampedValue, min, max, ariaLabel, onPointerDown, onKeyDown],
  );

  return { value: clampedValue, setValue: apply, dragging, getHandleProps };
}
