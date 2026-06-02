import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    // Heavier component tests (render + multiple userEvent interactions + React
    // re-renders) can exceed the 5s default on slower/loaded CI agents, even with
    // userEvent delay:null. Use a generous global timeout so CI-load latency does
    // not cause spurious "Test timed out in 5000ms" failures.
    testTimeout: 20000,
    hookTimeout: 20000,
    setupFiles: ['./src/test-setup.ts'],
    css: true,
    reporters: ['default', 'junit'],
    outputFile: {
      junit: 'test-reports/junit.xml',
    },
  },
});
