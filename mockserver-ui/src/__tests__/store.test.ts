import { describe, it, expect, beforeEach } from 'vitest';
import { useDashboardStore } from '../store';
import type { WebSocketMessage } from '../types';

describe('DashboardStore', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      logMessages: [],
      activeExpectations: [],
      recordedRequests: [],
      proxiedRequests: [],
      view: 'get-started',
      requestFilter: {},
      filterEnabled: false,
      filterExpanded: false,
      connectionStatus: 'disconnected',
      autoScroll: true,
      logSearch: '',
      expectationSearch: '',
      receivedSearch: '',
      proxiedSearch: '',
      trafficSearch: '',
      error: null,
      notification: null,
    });
  });

  describe('applyMessage', () => {
    it('replaces all entity arrays from a WebSocket message', () => {
      const message: WebSocketMessage = {
        logMessages: [{ key: 'log1', value: { messageParts: [] } }],
        activeExpectations: [{ key: 'exp1', value: { httpRequest: { path: '/test' } } }],
        recordedRequests: [{ key: 'rec1', value: { path: '/received' } }],
        proxiedRequests: [{ key: 'prx1', value: { path: '/proxied' } }],
      };

      useDashboardStore.getState().applyMessage(message);
      const state = useDashboardStore.getState();

      expect(state.logMessages).toHaveLength(1);
      expect(state.logMessages[0]!.key).toBe('log1');
      expect(state.activeExpectations).toHaveLength(1);
      expect(state.activeExpectations[0]!.key).toBe('exp1');
      expect(state.recordedRequests).toHaveLength(1);
      expect(state.proxiedRequests).toHaveLength(1);
    });

    it('defaults to empty arrays when fields are missing', () => {
      const message = {} as WebSocketMessage;
      useDashboardStore.getState().applyMessage(message);
      const state = useDashboardStore.getState();

      expect(state.logMessages).toEqual([]);
      expect(state.activeExpectations).toEqual([]);
      expect(state.recordedRequests).toEqual([]);
      expect(state.proxiedRequests).toEqual([]);
    });

    it('sets error from message', () => {
      const message: WebSocketMessage = {
        logMessages: [],
        activeExpectations: [],
        recordedRequests: [],
        proxiedRequests: [],
        error: 'invalid filter',
      };

      useDashboardStore.getState().applyMessage(message);
      expect(useDashboardStore.getState().error).toBe('invalid filter');
    });

    it('clears error when no error in message', () => {
      useDashboardStore.setState({ error: 'old error' });
      useDashboardStore.getState().applyMessage({
        logMessages: [],
        activeExpectations: [],
        recordedRequests: [],
        proxiedRequests: [],
      });
      expect(useDashboardStore.getState().error).toBeNull();
    });

    it('stays on get-started when the first data arrives (no auto-switch)', () => {
      // Start on get-started with no data (first-run scenario)
      useDashboardStore.setState({
        view: 'get-started',
        activeExpectations: [],
        recordedRequests: [],
        proxiedRequests: [],
      });

      useDashboardStore.getState().applyMessage({
        logMessages: [],
        activeExpectations: [{ key: 'e1', value: {} }],
        recordedRequests: [],
        proxiedRequests: [],
      });

      // Landing on Get Started is sticky — the user navigates away themselves.
      expect(useDashboardStore.getState().view).toBe('get-started');
    });

    it('does NOT bounce user off get-started when data already exists', () => {
      // User has navigated back to get-started while server has data
      useDashboardStore.setState({
        view: 'get-started',
        activeExpectations: [{ key: 'e1', value: {} }],
        recordedRequests: [],
        proxiedRequests: [],
      });

      // Another data message arrives — should NOT switch away
      useDashboardStore.getState().applyMessage({
        logMessages: [],
        activeExpectations: [{ key: 'e1', value: {} }, { key: 'e2', value: {} }],
        recordedRequests: [],
        proxiedRequests: [],
      });

      expect(useDashboardStore.getState().view).toBe('get-started');
    });
  });

  describe('reconcileByKey identity preservation', () => {
    it('keeps the previous object reference for an unchanged item across pushes', () => {
      // First push establishes the references.
      useDashboardStore.getState().applyMessage({
        logMessages: [],
        activeExpectations: [{ key: 'e1', value: { httpRequest: { path: '/a' } } }],
        recordedRequests: [],
        proxiedRequests: [],
      });
      const first = useDashboardStore.getState().activeExpectations[0]!;

      // Second push delivers a brand-new but semantically identical object.
      useDashboardStore.getState().applyMessage({
        logMessages: [],
        activeExpectations: [{ key: 'e1', value: { httpRequest: { path: '/a' } } }],
        recordedRequests: [],
        proxiedRequests: [],
      });
      const second = useDashboardStore.getState().activeExpectations[0]!;

      // Identity is preserved so React.memo'd rows can skip re-rendering.
      expect(second).toBe(first);
    });

    it('preserves identity across several pushes (cached string path)', () => {
      const push = () =>
        useDashboardStore.getState().applyMessage({
          logMessages: [],
          activeExpectations: [{ key: 'e1', value: { n: 1, nested: { deep: [1, 2, 3] } } }],
          recordedRequests: [],
          proxiedRequests: [],
        });
      push();
      const first = useDashboardStore.getState().activeExpectations[0]!;
      push();
      push();
      const third = useDashboardStore.getState().activeExpectations[0]!;
      expect(third).toBe(first);
    });

    it('replaces the reference when an item changes', () => {
      useDashboardStore.getState().applyMessage({
        logMessages: [],
        activeExpectations: [{ key: 'e1', value: { count: 1 } }],
        recordedRequests: [],
        proxiedRequests: [],
      });
      const first = useDashboardStore.getState().activeExpectations[0]!;

      useDashboardStore.getState().applyMessage({
        logMessages: [],
        activeExpectations: [{ key: 'e1', value: { count: 2 } }],
        recordedRequests: [],
        proxiedRequests: [],
      });
      const second = useDashboardStore.getState().activeExpectations[0]!;

      expect(second).not.toBe(first);
      expect(second.value).toEqual({ count: 2 });
    });

    it('preserves unchanged items while replacing changed ones in the same push', () => {
      useDashboardStore.getState().applyMessage({
        logMessages: [],
        activeExpectations: [
          { key: 'a', value: { v: 1 } },
          { key: 'b', value: { v: 1 } },
        ],
        recordedRequests: [],
        proxiedRequests: [],
      });
      const before = useDashboardStore.getState().activeExpectations;
      const aBefore = before.find((i) => i.key === 'a')!;
      const bBefore = before.find((i) => i.key === 'b')!;

      // 'a' unchanged, 'b' changed.
      useDashboardStore.getState().applyMessage({
        logMessages: [],
        activeExpectations: [
          { key: 'a', value: { v: 1 } },
          { key: 'b', value: { v: 2 } },
        ],
        recordedRequests: [],
        proxiedRequests: [],
      });
      const after = useDashboardStore.getState().activeExpectations;
      const aAfter = after.find((i) => i.key === 'a')!;
      const bAfter = after.find((i) => i.key === 'b')!;

      expect(aAfter).toBe(aBefore);
      expect(bAfter).not.toBe(bBefore);
    });

    it('adds new items and drops removed ones', () => {
      useDashboardStore.getState().applyMessage({
        logMessages: [],
        activeExpectations: [
          { key: 'a', value: { v: 1 } },
          { key: 'b', value: { v: 1 } },
        ],
        recordedRequests: [],
        proxiedRequests: [],
      });
      const aBefore = useDashboardStore.getState().activeExpectations.find((i) => i.key === 'a')!;

      // 'b' removed, 'c' added, 'a' unchanged.
      useDashboardStore.getState().applyMessage({
        logMessages: [],
        activeExpectations: [
          { key: 'a', value: { v: 1 } },
          { key: 'c', value: { v: 9 } },
        ],
        recordedRequests: [],
        proxiedRequests: [],
      });
      const after = useDashboardStore.getState().activeExpectations;

      expect(after.map((i) => i.key)).toEqual(['a', 'c']);
      expect(after.find((i) => i.key === 'a')!).toBe(aBefore); // identity preserved
      expect(after.find((i) => i.key === 'c')!.value).toEqual({ v: 9 });
    });

    it('detects a change even after a direct setState bypasses the reconcile cache', () => {
      // Seed via a normal push so the cache holds e1's string.
      useDashboardStore.getState().applyMessage({
        logMessages: [],
        activeExpectations: [{ key: 'e1', value: { v: 1 } }],
        recordedRequests: [],
        proxiedRequests: [],
      });
      // Bypass reconcile entirely — cache now points at a stale reference.
      useDashboardStore.setState({ activeExpectations: [{ key: 'e1', value: { v: 2 } }] });
      const direct = useDashboardStore.getState().activeExpectations[0]!;

      // An identical-to-the-direct-state item must preserve the direct reference,
      // NOT be fooled into a miss by the stale cached string from the first push.
      useDashboardStore.getState().applyMessage({
        logMessages: [],
        activeExpectations: [{ key: 'e1', value: { v: 2 } }],
        recordedRequests: [],
        proxiedRequests: [],
      });
      expect(useDashboardStore.getState().activeExpectations[0]!).toBe(direct);
    });

    it('preserves identity for nested log groups', () => {
      useDashboardStore.getState().applyMessage({
        logMessages: [
          {
            key: 'g1',
            group: { key: 'g1', value: { messageParts: [] } },
            value: [{ key: 'g1-1', value: { messageParts: [] } }],
          },
        ],
        activeExpectations: [],
        recordedRequests: [],
        proxiedRequests: [],
      });
      const first = useDashboardStore.getState().logMessages[0]!;

      useDashboardStore.getState().applyMessage({
        logMessages: [
          {
            key: 'g1',
            group: { key: 'g1', value: { messageParts: [] } },
            value: [{ key: 'g1-1', value: { messageParts: [] } }],
          },
        ],
        activeExpectations: [],
        recordedRequests: [],
        proxiedRequests: [],
      });
      expect(useDashboardStore.getState().logMessages[0]!).toBe(first);
    });
  });

  describe('clearUI', () => {
    it('empties all entity arrays and clears error', () => {
      useDashboardStore.setState({
        logMessages: [{ key: 'l', value: {} }],
        activeExpectations: [{ key: 'e', value: {} }],
        recordedRequests: [{ key: 'r', value: {} }],
        proxiedRequests: [{ key: 'p', value: {} }],
        error: 'some error',
      });

      useDashboardStore.getState().clearUI();
      const state = useDashboardStore.getState();

      expect(state.logMessages).toEqual([]);
      expect(state.activeExpectations).toEqual([]);
      expect(state.recordedRequests).toEqual([]);
      expect(state.proxiedRequests).toEqual([]);
      expect(state.error).toBeNull();
    });

    it('resets view to get-started after server reset', () => {
      useDashboardStore.setState({
        view: 'dashboard',
        activeExpectations: [{ key: 'e', value: {} }],
        recordedRequests: [{ key: 'r', value: {} }],
        proxiedRequests: [{ key: 'p', value: {} }],
      });

      useDashboardStore.getState().clearUI();
      expect(useDashboardStore.getState().view).toBe('get-started');
    });
  });

  describe('filter state', () => {
    it('setFilterEnabled updates enabled flag', () => {
      useDashboardStore.getState().setFilterEnabled(true);
      expect(useDashboardStore.getState().filterEnabled).toBe(true);
    });

    it('toggleFilterExpanded toggles the flag', () => {
      expect(useDashboardStore.getState().filterExpanded).toBe(false);
      useDashboardStore.getState().toggleFilterExpanded();
      expect(useDashboardStore.getState().filterExpanded).toBe(true);
      useDashboardStore.getState().toggleFilterExpanded();
      expect(useDashboardStore.getState().filterExpanded).toBe(false);
    });

    it('setRequestFilter updates the filter', () => {
      useDashboardStore.getState().setRequestFilter({ method: 'GET', path: '/api' });
      expect(useDashboardStore.getState().requestFilter).toEqual({ method: 'GET', path: '/api' });
    });
  });

  describe('UI state', () => {
    it('toggleAutoScroll toggles the flag', () => {
      expect(useDashboardStore.getState().autoScroll).toBe(true);
      useDashboardStore.getState().toggleAutoScroll();
      expect(useDashboardStore.getState().autoScroll).toBe(false);
    });

    it('setConnectionStatus updates the status', () => {
      useDashboardStore.getState().setConnectionStatus('connected');
      expect(useDashboardStore.getState().connectionStatus).toBe('connected');
    });

    it('search setters update their respective fields', () => {
      useDashboardStore.getState().setLogSearch('error');
      useDashboardStore.getState().setExpectationSearch('path');
      useDashboardStore.getState().setReceivedSearch('POST');
      useDashboardStore.getState().setProxiedSearch('forward');

      const state = useDashboardStore.getState();
      expect(state.logSearch).toBe('error');
      expect(state.expectationSearch).toBe('path');
      expect(state.receivedSearch).toBe('POST');
      expect(state.proxiedSearch).toBe('forward');
    });
  });

  describe('theme', () => {
    it('toggleThemeMode switches between dark and light', () => {
      useDashboardStore.setState({ themeMode: 'dark' });
      useDashboardStore.getState().toggleThemeMode();
      expect(useDashboardStore.getState().themeMode).toBe('light');
      useDashboardStore.getState().toggleThemeMode();
      expect(useDashboardStore.getState().themeMode).toBe('dark');
    });
  });
});
