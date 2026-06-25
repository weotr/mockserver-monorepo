import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const mockserverTarget = process.env.MOCKSERVER_URL || 'http://localhost:1080';

// Inject the dashboard's own version (from package.json) as a build-time
// constant so the analytics module can report `app_version` without a runtime
// fetch. Falls back to 'unknown' if package.json cannot be read.
function readAppVersion(): string {
  try {
    const pkg = JSON.parse(
      readFileSync(fileURLToPath(new URL('./package.json', import.meta.url)), 'utf8'),
    ) as { version?: string };
    return typeof pkg.version === 'string' && pkg.version.length > 0 ? pkg.version : 'unknown';
  } catch {
    return 'unknown';
  }
}

export default defineConfig({
  plugins: [react()],
  base: '/mockserver/dashboard/',
  define: {
    __APP_VERSION__: JSON.stringify(readAppVersion()),
  },
  build: {
    outDir: 'build',
  },
  server: {
    port: 3000,
    proxy: {
      '/_mockserver_ui_websocket': {
        target: mockserverTarget,
        ws: true,
      },
      '/mockserver': {
        target: mockserverTarget,
        bypass(req) {
          if (req.url?.startsWith('/mockserver/dashboard')) {
            return req.url;
          }
        },
      },
    },
  },
});
