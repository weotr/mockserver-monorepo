import { createContext, useContext } from 'react';

/**
 * Seed a new breakpoint matcher from a request's method + path. Invoked from a
 * log row's "Set breakpoint" action; the provider switches to the Breakpoints
 * view and pre-fills the matcher form. Either field may be absent when the row
 * does not expose it.
 */
export type SetBreakpointFn = (prefill: { method?: string; path?: string }) => void;

export const SetBreakpointContext = createContext<SetBreakpointFn | null>(null);

export function useSetBreakpointContext(): SetBreakpointFn | null {
  return useContext(SetBreakpointContext);
}
