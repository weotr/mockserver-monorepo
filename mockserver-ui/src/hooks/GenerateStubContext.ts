import { createContext, useContext } from 'react';

export type GenerateStubFn = (request: Record<string, unknown>) => Promise<void>;

export const GenerateStubContext = createContext<GenerateStubFn | null>(null);

export function useGenerateStubContext(): GenerateStubFn | null {
  return useContext(GenerateStubContext);
}
