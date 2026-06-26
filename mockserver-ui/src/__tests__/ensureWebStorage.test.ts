import { describe, it, expect } from 'vitest';
import { ensureWebStorage } from '../test-setup-storage';

/**
 * Guards the test-environment Storage shim that fixes the flaky
 * "localStorage is undefined" failures (jsdom omits Web Storage under some
 * origins/installs). Uses a controlled target object so the proof is
 * deterministic and never depends on the ambient jsdom state.
 */
describe('ensureWebStorage', () => {
  it('installs a fully working in-memory localStorage when absent', () => {
    const target = {} as unknown as typeof globalThis;
    ensureWebStorage(target);

    const ls = target.localStorage;
    expect(ls).toBeDefined();
    // full Storage surface the suites rely on
    expect(ls.length).toBe(0);
    ls.setItem('a', '1');
    ls.setItem('b', '2');
    expect(ls.getItem('a')).toBe('1');
    expect(ls.length).toBe(2);
    expect([ls.key(0), ls.key(1)].sort()).toEqual(['a', 'b']);
    expect(ls.key(99)).toBeNull();
    ls.removeItem('a');
    expect(ls.getItem('a')).toBeNull();
    expect(ls.length).toBe(1);
    ls.clear();
    expect(ls.length).toBe(0);
    expect(ls.getItem('b')).toBeNull();
  });

  it('also provides sessionStorage', () => {
    const target = {} as unknown as typeof globalThis;
    ensureWebStorage(target);
    expect(typeof target.sessionStorage.setItem).toBe('function');
    target.sessionStorage.setItem('k', 'v');
    expect(target.sessionStorage.getItem('k')).toBe('v');
  });

  it('does not replace an already-working Storage', () => {
    const existing = {
      clear() {},
      getItem() {
        return 'sentinel';
      },
      setItem() {},
      removeItem() {},
      key() {
        return null;
      },
      length: 0,
    } as unknown as Storage;
    const target = { localStorage: existing } as unknown as typeof globalThis;
    ensureWebStorage(target);
    expect(target.localStorage).toBe(existing);
    expect(target.localStorage.getItem('anything')).toBe('sentinel');
  });

  it('replaces a slot whose access throws (opaque-origin SecurityError)', () => {
    const target = {} as unknown as typeof globalThis;
    Object.defineProperty(target, 'localStorage', {
      configurable: true,
      get() {
        throw new Error('SecurityError: opaque origin');
      },
    });
    expect(() => ensureWebStorage(target)).not.toThrow();
    // after the shim is installed the getter no longer throws and works
    expect(() => target.localStorage.setItem('a', 'b')).not.toThrow();
    expect(target.localStorage.getItem('a')).toBe('b');
  });

  it('the real test environment has a working localStorage (shim or jsdom)', () => {
    // This asserts the setup file (which calls ensureWebStorage()) leaves the
    // global usable — the actual regression the fix targets.
    expect(() => {
      localStorage.setItem('selftest', '1');
      localStorage.clear();
    }).not.toThrow();
  });
});
