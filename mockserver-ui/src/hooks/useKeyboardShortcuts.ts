import { useEffect } from 'react';

interface ShortcutHandlers {
  onSearch: () => void;
  onClear: () => void;
  onToggleFilter: () => void;
}

/** True when the event targets a text field / contenteditable, or an IME is composing. */
function isTypingContext(e: KeyboardEvent): boolean {
  if (e.isComposing) return true;
  const el = e.target as HTMLElement | null;
  if (!el || typeof el.tagName !== 'string') return false;
  const tag = el.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable === true;
}

export function useKeyboardShortcuts(handlers: ShortcutHandlers) {
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      const mod = e.metaKey || e.ctrlKey;

      // Never fire mod-key shortcuts while the user is typing in a field or composing via an
      // IME — Cmd/Ctrl+L maps to a full server reset and must not trigger from a stray keypress.
      if (mod && (e.key === 'k' || e.key === 'l')) {
        if (isTypingContext(e)) return;
        e.preventDefault();
        if (e.key === 'k') handlers.onSearch();
        else handlers.onClear();
        return;
      }

      if (e.key === 'Escape' && !isTypingContext(e)) {
        handlers.onToggleFilter();
      }
    }

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [handlers]);
}
