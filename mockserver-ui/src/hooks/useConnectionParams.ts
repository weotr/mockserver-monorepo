import { useMemo } from 'react';

export interface ConnectionParams {
  host: string;
  port: string;
  secure: boolean;
  /**
   * Context/base path the dashboard is served under (e.g. behind a reverse proxy), without a
   * trailing slash. Prefixed to every REST and WebSocket URL so actions reach the server even
   * when it is not at the host root. Empty string when served at the root. Optional so existing
   * { host, port, secure } literals (tests, component props) remain assignable.
   */
  basePath?: string;
}

export function useConnectionParams(): ConnectionParams {
  return useMemo(() => {
    const params = new URLSearchParams(window.location.search);
    const host =
      params.get('host') || window.location.hostname || '127.0.0.1';
    const port =
      params.get('port') ||
      window.location.port ||
      (window.location.protocol === 'https:' ? '443' : '80');
    const secure = window.location.protocol === 'https:';
    // Derive the base path from the dashboard URL: the segment before the trailing
    // /mockserver/dashboard. Only strip when that suffix is actually present so an unexpected
    // path is not misread as a base path; otherwise assume root (''). A ?basePath= query param
    // overrides it (useful when host/port are also overridden).
    const pathMatch = /^(.*)\/mockserver\/dashboard\/?$/.exec(window.location.pathname ?? '');
    const basePath = (params.get('basePath') ?? (pathMatch ? (pathMatch[1] ?? '') : '')).replace(/\/+$/, '');
    return { host, port, secure, basePath };
  }, []);
}
