/**
 * Guarantees `localStorage` / `sessionStorage` exist under the test environment.
 *
 * jsdom only exposes the Web Storage APIs when the document has a non-opaque
 * origin, and different jsdom versions/installs differ in whether they provide
 * them at all. When the installed node_modules drift from the lockfile (a stale
 * `node_modules` that was not `npm ci`-ed), several suites that `localStorage.clear()`
 * in `beforeEach` blew up with "localStorage is undefined" — a flaky failure that
 * depended on install state rather than code. This installs a minimal in-memory
 * Storage shim ONLY when a working one is absent, so the tests are deterministic
 * regardless of the jsdom build. A real, working jsdom Storage is left untouched.
 */

function createMemoryStorage(): Storage {
  const store = new Map<string, string>();
  const storage: Storage = {
    get length() {
      return store.size;
    },
    clear() {
      store.clear();
    },
    getItem(key: string) {
      return store.has(key) ? store.get(key)! : null;
    },
    key(index: number) {
      return Array.from(store.keys())[index] ?? null;
    },
    removeItem(key: string) {
      store.delete(key);
    },
    setItem(key: string, value: string) {
      store.set(String(key), String(value));
    },
  };
  return storage;
}

/** Returns true if `value` is a usable Storage (has the methods the tests call). */
function isWorkingStorage(value: unknown): value is Storage {
  return (
    !!value &&
    typeof (value as Storage).clear === 'function' &&
    typeof (value as Storage).getItem === 'function' &&
    typeof (value as Storage).setItem === 'function'
  );
}

/**
 * Ensure `localStorage` and `sessionStorage` are present and working on `target`.
 * A no-op for any slot that already holds a working Storage. Accessing the slot
 * can itself throw (SecurityError under an opaque origin), which is treated as
 * "absent" and replaced with the shim. Defaults to `globalThis`.
 */
export function ensureWebStorage(target: typeof globalThis = globalThis): void {
  for (const prop of ['localStorage', 'sessionStorage'] as const) {
    let existing: unknown;
    try {
      existing = (target as unknown as Record<string, unknown>)[prop];
    } catch {
      existing = undefined; // opaque-origin access can throw — treat as absent
    }
    if (isWorkingStorage(existing)) continue;

    const storage = createMemoryStorage();
    Object.defineProperty(target, prop, { value: storage, writable: true, configurable: true });

    // In jsdom `globalThis === window`, but if they are distinct mirror the shim
    // onto window so `window.localStorage` resolves to the same instance.
    const win = (target as unknown as { window?: typeof globalThis }).window;
    if (win && win !== target) {
      try {
        Object.defineProperty(win, prop, { value: storage, writable: true, configurable: true });
      } catch {
        /* ignore — best effort */
      }
    }
  }
}
