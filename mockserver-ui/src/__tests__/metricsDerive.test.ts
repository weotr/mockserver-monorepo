import { describe, it, expect } from 'vitest';
import { gaugeSeries, gaugeSeriesByLabel, gaugeSeriesSum, ratePerSecond, latestRate, type MetricsSnapshot } from '../lib/metricsDerive';

function snap(at: number, received: number): MetricsSnapshot {
  return { at, samples: [{ name: 'requests_received_count', labels: {}, value: received }] };
}

describe('metricsDerive', () => {
  it('gaugeSeries extracts a gauge across snapshots', () => {
    const history = [snap(0, 10), snap(1000, 25), snap(2000, 40)];
    expect(gaugeSeries(history, 'requests_received_count')).toEqual([10, 25, 40]);
  });

  it('gaugeSeries returns 0 for snapshots missing the metric', () => {
    const history: MetricsSnapshot[] = [{ at: 0, samples: [] }];
    expect(gaugeSeries(history, 'requests_received_count')).toEqual([0]);
  });

  it('ratePerSecond computes per-second deltas and yields N-1 points', () => {
    // +15 over 1s, +30 over 2s => 15 rps, 15 rps
    const history = [snap(0, 10), snap(1000, 25), snap(3000, 55)];
    expect(ratePerSecond(history, 'requests_received_count')).toEqual([15, 15]);
  });

  it('ratePerSecond clamps negative deltas (server reset) to 0', () => {
    const history = [snap(0, 100), snap(1000, 5)];
    expect(ratePerSecond(history, 'requests_received_count')).toEqual([0]);
  });

  it('ratePerSecond is empty for fewer than 2 snapshots', () => {
    expect(ratePerSecond([snap(0, 10)], 'requests_received_count')).toEqual([]);
    expect(ratePerSecond([], 'requests_received_count')).toEqual([]);
  });

  it('gaugeSeriesByLabel extracts a label-scoped gauge across snapshots', () => {
    const history: MetricsSnapshot[] = [
      { at: 0, samples: [{ name: 'jvm_memory_used_bytes', labels: { area: 'heap' }, value: 100 }] },
      { at: 1000, samples: [{ name: 'jvm_memory_used_bytes', labels: { area: 'heap' }, value: 150 }] },
    ];
    expect(gaugeSeriesByLabel(history, 'jvm_memory_used_bytes', 'area', 'heap')).toEqual([100, 150]);
    expect(gaugeSeriesByLabel(history, 'jvm_memory_used_bytes', 'area', 'nonheap')).toEqual([0, 0]);
  });

  it('latestRate returns the most recent rate or 0', () => {
    const history = [snap(0, 0), snap(1000, 10), snap(2000, 35)];
    expect(latestRate(history, 'requests_received_count')).toBe(25);
    expect(latestRate([snap(0, 0)], 'requests_received_count')).toBe(0);
  });

  it('gaugeSeriesByLabel tracks expectations_by_type per action_type over time', () => {
    const history: MetricsSnapshot[] = [
      {
        at: 0,
        samples: [
          { name: 'mock_server_expectations_by_type', labels: { action_type: 'RESPONSE' }, value: 3 },
          { name: 'mock_server_expectations_by_type', labels: { action_type: 'FORWARD' }, value: 1 },
        ],
      },
      {
        at: 1000,
        samples: [
          { name: 'mock_server_expectations_by_type', labels: { action_type: 'RESPONSE' }, value: 5 },
          { name: 'mock_server_expectations_by_type', labels: { action_type: 'FORWARD' }, value: 2 },
        ],
      },
    ];
    expect(gaugeSeriesByLabel(history, 'mock_server_expectations_by_type', 'action_type', 'RESPONSE')).toEqual([3, 5]);
    expect(gaugeSeriesByLabel(history, 'mock_server_expectations_by_type', 'action_type', 'FORWARD')).toEqual([1, 2]);
    // absent label value returns zeros
    expect(gaugeSeriesByLabel(history, 'mock_server_expectations_by_type', 'action_type', 'ERROR')).toEqual([0, 0]);
  });

  it('gaugeSeriesByLabel tracks mcp_tool_calls_total per tool over time', () => {
    const history: MetricsSnapshot[] = [
      {
        at: 0,
        samples: [
          { name: 'mock_server_mcp_tool_calls_total', labels: { tool: 'list_mock_tools' }, value: 5 },
          { name: 'mock_server_mcp_tool_calls_total', labels: { tool: 'create_expectation' }, value: 2 },
        ],
      },
      {
        at: 1000,
        samples: [
          { name: 'mock_server_mcp_tool_calls_total', labels: { tool: 'list_mock_tools' }, value: 8 },
          { name: 'mock_server_mcp_tool_calls_total', labels: { tool: 'create_expectation' }, value: 3 },
        ],
      },
    ];
    expect(gaugeSeriesByLabel(history, 'mock_server_mcp_tool_calls_total', 'tool', 'list_mock_tools')).toEqual([5, 8]);
    expect(gaugeSeriesByLabel(history, 'mock_server_mcp_tool_calls_total', 'tool', 'create_expectation')).toEqual([2, 3]);
  });

  it('gaugeSeriesSum totals a labeled counter across all label values per snapshot', () => {
    const history: MetricsSnapshot[] = [
      {
        at: 0,
        samples: [
          { name: 'mock_server_async_messages_published_total', labels: { channel: 'orders/placed' }, value: 6 },
          { name: 'mock_server_async_messages_published_total', labels: { channel: 'payments/processed' }, value: 4 },
        ],
      },
      {
        at: 1000,
        samples: [
          { name: 'mock_server_async_messages_published_total', labels: { channel: 'orders/placed' }, value: 9 },
          { name: 'mock_server_async_messages_published_total', labels: { channel: 'payments/processed' }, value: 6 },
        ],
      },
    ];
    // 6+4 = 10, then 9+6 = 15
    expect(gaugeSeriesSum(history, 'mock_server_async_messages_published_total')).toEqual([10, 15]);
  });

  it('gaugeSeriesSum returns 0 for snapshots missing the metric', () => {
    const history: MetricsSnapshot[] = [{ at: 0, samples: [] }];
    expect(gaugeSeriesSum(history, 'mock_server_async_messages_published_total')).toEqual([0]);
  });
});
