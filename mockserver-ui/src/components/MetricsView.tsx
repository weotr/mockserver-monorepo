import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import CircularProgress from '@mui/material/CircularProgress';
import RefreshIcon from '@mui/icons-material/Refresh';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { useMetricsPolling } from '../hooks/useMetricsPolling';
import { findSample, metricValue, metricValueByLabel, hasMetric, labelValues } from '../lib/prometheusParser';
import { gaugeSeries, gaugeSeriesByLabel, ratePerSecond, latestRate } from '../lib/metricsDerive';
import { histogramQuantile } from '../lib/histogramQuantile';
import MetricsLineChart from './MetricsLineChart';

const LATENCY_METRIC = 'mock_server_request_duration_seconds';
const CHAOS_METRIC = 'mock_server_http_chaos_injected_total';
const ACTIVE_CHAOS_METRIC = 'mock_server_active_service_chaos';
const EXPECTATIONS_BY_TYPE_METRIC = 'mock_server_expectations_by_type';
const MCP_TOOL_CALLS_METRIC = 'mock_server_mcp_tool_calls_total';

// All HTTP chaos fault types, in display order. Any fault_type the server emits
// that is not listed here still renders (appended, title-cased) so the UI never
// silently drops a future fault type.
const CHAOS_FAULT_ORDER = ['drop', 'error', 'latency', 'truncate', 'malformed', 'slow', 'quota'];

function chaosFaultLabel(faultType: string): string {
  return faultType.charAt(0).toUpperCase() + faultType.slice(1);
}

/** Converts e.g. "RESPONSE_TEMPLATE" to "Response template". */
function prettyEnumLabel(raw: string): string {
  return raw
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/^./, (c) => c.toUpperCase());
}

/** fault_type values present in the metric, ordered by CHAOS_FAULT_ORDER then first-seen. */
function orderedFaultTypes(present: string[]): string[] {
  const known = CHAOS_FAULT_ORDER.filter((ft) => present.includes(ft));
  const extra = present.filter((ft) => !CHAOS_FAULT_ORDER.includes(ft));
  return [...known, ...extra];
}

interface MetricsViewProps {
  connectionParams: ConnectionParams;
}

const SUMMARY: { name: string; label: string }[] = [
  { name: 'requests_received_count', label: 'Requests received' },
  { name: 'response_expectations_matched_count', label: 'Matched' },
  { name: 'expectations_not_matched_count', label: 'Not matched' },
  { name: 'forward_expectations_matched_count', label: 'Forwarded' },
];

function prettyActionName(metric: string): string {
  return metric
    .replace(/_count$/, '')
    .replace(/_/g, ' ')
    .trim();
}

function formatBytes(bytes: number): string {
  if (bytes < 0 || !Number.isFinite(bytes)) return '—';
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let i = 0;
  while (value >= 1024 && i < units.length - 1) {
    value /= 1024;
    i += 1;
  }
  return `${value.toFixed(i === 0 ? 0 : 1)} ${units[i] ?? 'B'}`;
}

export default function MetricsView({ connectionParams }: MetricsViewProps) {
  const { status, history, latest, error, intervalMs, refresh } = useMetricsPolling(connectionParams, {
    intervalMs: 3000,
    historySize: 60,
  });

  const version = latest ? findSample(latest.samples, 'mock_server_build_info')?.labels.version : undefined;
  const rps = latestRate(history, 'requests_received_count');

  const actionRows = latest
    ? latest.samples
        .filter((s) => s.name.endsWith('_actions_count'))
        .map((s) => ({ name: s.name, label: prettyActionName(s.name), value: s.value }))
        .sort((a, b) => b.value - a.value)
    : [];
  const maxAction = actionRows.reduce((m, r) => Math.max(m, r.value), 0);
  const jvmEnabled = latest ? hasMetric(latest.samples, 'jvm_memory_used_bytes') : false;
  const latencyEnabled = latest ? hasMetric(latest.samples, `${LATENCY_METRIC}_count`) : false;
  const chaosFaultTypes = latest ? orderedFaultTypes(labelValues(latest.samples, CHAOS_METRIC, 'fault_type')) : [];
  const chaosFaultTotals = latest
    ? chaosFaultTypes.map((ft) => ({ faultType: ft, value: metricValueByLabel(latest.samples, CHAOS_METRIC, 'fault_type', ft) }))
    : [];
  // Active service-scoped chaos is a gauge labeled by fault_type (one series per
  // type), so it is charted by type rather than shown as a single counter.
  const activeChaosFaultTypes = latest ? orderedFaultTypes(labelValues(latest.samples, ACTIVE_CHAOS_METRIC, 'fault_type')) : [];
  const activeServiceChaosEnabled = activeChaosFaultTypes.length > 0;
  const activeChaosTotal = activeChaosFaultTypes.reduce(
    (sum, ft) => sum + (latest ? metricValueByLabel(latest.samples, ACTIVE_CHAOS_METRIC, 'fault_type', ft) : 0),
    0,
  );
  const chaosEnabled = latest ? (hasMetric(latest.samples, CHAOS_METRIC) || activeServiceChaosEnabled) : false;
  const chaosHasData = chaosFaultTotals.some((f) => f.value > 0) || activeChaosTotal > 0;

  // Expectations by type — gauge labeled by action_type
  const expectationActionTypes = latest
    ? labelValues(latest.samples, EXPECTATIONS_BY_TYPE_METRIC, 'action_type')
    : [];
  const expectationsByType = latest
    ? expectationActionTypes
        .map((at) => ({ actionType: at, value: metricValueByLabel(latest.samples, EXPECTATIONS_BY_TYPE_METRIC, 'action_type', at) }))
        .filter((r) => r.value > 0)
        .sort((a, b) => b.value - a.value)
    : [];
  const expectationsByTypeEnabled = expectationsByType.length > 0;

  // MCP tool calls — counter labeled by tool (shown cumulative like actions-executed)
  const mcpToolNames = latest
    ? labelValues(latest.samples, MCP_TOOL_CALLS_METRIC, 'tool')
    : [];
  const mcpToolRows = latest
    ? mcpToolNames
        .map((t) => ({ tool: t, value: metricValueByLabel(latest.samples, MCP_TOOL_CALLS_METRIC, 'tool', t) }))
        .filter((r) => r.value > 0)
        .sort((a, b) => b.value - a.value)
    : [];
  const mcpToolCallsEnabled = mcpToolRows.length > 0;

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          Metrics
        </Typography>
        <Chip
          size="small"
          label={status === 'ok' ? 'live' : status}
          color={status === 'ok' ? 'success' : status === 'disabled' ? 'default' : status === 'error' ? 'error' : 'warning'}
          variant="outlined"
        />
        {version && <Chip size="small" label={`MockServer ${version}`} variant="outlined" />}
        <Box sx={{ flex: 1 }} />
        {latest && (
          <Typography variant="caption" color="text.secondary">
            updated {new Date(latest.at).toLocaleTimeString()} · every {Math.round(intervalMs / 1000)}s
          </Typography>
        )}
        <Tooltip title="Refresh now">
          <IconButton size="small" onClick={refresh} aria-label="Refresh metrics">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      {status === 'disabled' && (
        <Alert severity="info" sx={{ mb: 1.5 }}>
          <AlertTitle>Metrics are disabled</AlertTitle>
          Start MockServer with metrics enabled to view live metrics here:
          <Box component="pre" sx={{ mt: 1, mb: 0, p: 1, bgcolor: 'action.hover', borderRadius: 1, fontSize: '0.75rem', overflow: 'auto' }}>
{`-Dmockserver.metricsEnabled=true
# or environment variable:
MOCKSERVER_METRICS_ENABLED=true`}
          </Box>
        </Alert>
      )}

      {status === 'error' && (
        <Alert severity="error" sx={{ mb: 1.5 }} action={
          <IconButton color="inherit" size="small" onClick={refresh} aria-label="Retry"><RefreshIcon fontSize="small" /></IconButton>
        }>
          <AlertTitle>Could not load metrics</AlertTitle>
          {error}
        </Alert>
      )}

      {status === 'loading' && history.length === 0 && (
        <Box sx={{ display: 'flex', justifyContent: 'center', p: 4 }}>
          <CircularProgress size={28} />
        </Box>
      )}

      {latest && (
        <>
          {/* Throughput */}
          <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
            <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1 }}>
              <Typography variant="caption" color="text.secondary">Throughput (derived)</Typography>
              <Typography variant="h6" sx={{ fontWeight: 700 }}>{rps.toFixed(1)} req/s</Typography>
            </Box>
            <MetricsLineChart
              height={180}
              series={[{ data: ratePerSecond(history, 'requests_received_count'), label: 'req/s' }]}
              valueFormatter={(v) => `${v.toFixed(1)}/s`}
            />
          </Paper>

          {/* Request latency (only when the server exposes the duration histogram) */}
          {latencyEnabled && latest && (
            <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
              <Typography variant="caption" color="text.secondary">Request latency — cumulative since server start</Typography>
              <Box sx={{ display: 'flex', gap: 3, mt: 0.5, flexWrap: 'wrap' }}>
                {([['p50', 0.5], ['p95', 0.95], ['p99', 0.99]] as const).map(([label, q]) => {
                  const seconds = histogramQuantile(latest.samples, LATENCY_METRIC, q);
                  return (
                    <Box key={label}>
                      <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
                        {seconds == null ? '—' : `${(seconds * 1000).toFixed(1)} ms`}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">{label}</Typography>
                    </Box>
                  );
                })}
              </Box>
              <Box sx={{ mt: 1 }}>
                <MetricsLineChart
                  height={140}
                  valueFormatter={(v) => `${v.toFixed(1)} ms`}
                  series={[{
                    data: history.map((snapshot) => {
                      const seconds = histogramQuantile(snapshot.samples, LATENCY_METRIC, 0.95);
                      return seconds == null ? 0 : seconds * 1000;
                    }),
                    label: 'p95 ms (cumulative)',
                  }]}
                />
              </Box>
            </Paper>
          )}

          {/* HTTP Chaos Faults (only when a chaos metric is present and has non-zero data) */}
          {chaosEnabled && chaosHasData && (
            <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
              <Typography variant="caption" color="text.secondary">HTTP Chaos Faults</Typography>
              {chaosFaultTotals.length > 0 && (
                <Box sx={{ display: 'flex', gap: 3, mt: 0.5, flexWrap: 'wrap' }}>
                  {chaosFaultTotals.map(({ faultType, value }) => (
                    <Box key={faultType}>
                      <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
                        {value.toLocaleString()}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">{faultType} faults</Typography>
                    </Box>
                  ))}
                </Box>
              )}
              {chaosFaultTypes.length > 0 && (
                <Box sx={{ mt: 1 }}>
                  <Typography variant="caption" color="text.secondary">Faults injected by type (cumulative)</Typography>
                  <MetricsLineChart
                    height={180}
                    valueFormatter={(v) => Math.round(v).toLocaleString()}
                    series={chaosFaultTypes.map((ft) => ({
                      data: gaugeSeriesByLabel(history, CHAOS_METRIC, 'fault_type', ft),
                      label: chaosFaultLabel(ft),
                    }))}
                  />
                </Box>
              )}
              {activeServiceChaosEnabled && (
                <Box sx={{ mt: 1 }}>
                  <Typography variant="caption" color="text.secondary">Active service-scoped chaos by type</Typography>
                  <MetricsLineChart
                    height={180}
                    valueFormatter={(v) => Math.round(v).toLocaleString()}
                    series={activeChaosFaultTypes.map((ft) => ({
                      data: gaugeSeriesByLabel(history, ACTIVE_CHAOS_METRIC, 'fault_type', ft),
                      label: chaosFaultLabel(ft),
                    }))}
                  />
                </Box>
              )}
            </Paper>
          )}

          {/* Request activity over time — the four request counters as separate lines */}
          <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
            <Typography variant="caption" color="text.secondary">Request activity (cumulative)</Typography>
            <MetricsLineChart
              height={200}
              valueFormatter={(v) => Math.round(v).toLocaleString()}
              series={SUMMARY.map(({ name, label }) => ({ data: gaugeSeries(history, name), label }))}
            />
          </Paper>

          {/* Actions executed over time — one line per action type */}
          <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
            <Typography variant="caption" color="text.secondary">Actions executed</Typography>
            {maxAction === 0 ? (
              <Typography variant="body2" sx={{ mt: 0.5 }} color="text.secondary">
                No actions executed yet.
              </Typography>
            ) : (
              <MetricsLineChart
                height={200}
                valueFormatter={(v) => Math.round(v).toLocaleString()}
                series={actionRows
                  .filter((r) => r.value > 0)
                  .map((r) => ({
                    data: gaugeSeries(history, r.name),
                    label: r.label.charAt(0).toUpperCase() + r.label.slice(1),
                  }))}
              />
            )}
          </Paper>

          {/* Expectations by type — one line per action_type label (gauge) */}
          <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
            <Typography variant="caption" color="text.secondary">Expectations by type</Typography>
            {!expectationsByTypeEnabled ? (
              <Typography variant="body2" sx={{ mt: 0.5 }} color="text.secondary">
                No expectations configured.
              </Typography>
            ) : (
              <MetricsLineChart
                height={200}
                valueFormatter={(v) => Math.round(v).toLocaleString()}
                series={expectationsByType.map((r) => ({
                  data: gaugeSeriesByLabel(history, EXPECTATIONS_BY_TYPE_METRIC, 'action_type', r.actionType),
                  label: prettyEnumLabel(r.actionType),
                }))}
              />
            )}
          </Paper>

          {/* MCP tool calls — one line per tool label (counter, shown cumulative) */}
          <Paper variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
            <Typography variant="caption" color="text.secondary">MCP tool calls</Typography>
            {!mcpToolCallsEnabled ? (
              <Typography variant="body2" sx={{ mt: 0.5 }} color="text.secondary">
                No MCP tool calls recorded.
              </Typography>
            ) : (
              <MetricsLineChart
                height={200}
                valueFormatter={(v) => Math.round(v).toLocaleString()}
                series={mcpToolRows.map((r) => ({
                  data: gaugeSeriesByLabel(history, MCP_TOOL_CALLS_METRIC, 'tool', r.tool),
                  label: r.tool,
                }))}
              />
            )}
          </Paper>

          {/* JVM runtime (Memory, Threads & GC) — at the bottom; only when the server exposes JVM metrics */}
          {jvmEnabled && latest && (
            <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 1 }}>
              <Paper variant="outlined" sx={{ p: 1.25 }}>
                <Typography variant="caption" color="text.secondary">JVM heap memory</Typography>
                <MetricsLineChart
                  height={200}
                  valueFormatter={formatBytes}
                  series={[
                    { data: gaugeSeriesByLabel(history, 'jvm_memory_used_bytes', 'area', 'heap'), label: 'used' },
                    { data: gaugeSeriesByLabel(history, 'jvm_memory_committed_bytes', 'area', 'heap'), label: 'committed' },
                  ]}
                />
              </Paper>
              <Paper variant="outlined" sx={{ p: 1.25 }}>
                <Typography variant="caption" color="text.secondary">Threads &amp; GC</Typography>
                <Box sx={{ display: 'flex', gap: 3, mt: 0.5, flexWrap: 'wrap' }}>
                  <Box>
                    <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
                      {metricValue(latest.samples, 'jvm_threads_current').toLocaleString()}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      threads ({metricValue(latest.samples, 'jvm_threads_daemon')} daemon)
                    </Typography>
                  </Box>
                  <Box>
                    <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
                      {metricValue(latest.samples, 'jvm_gc_collection_count').toLocaleString()}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      GC collections ({metricValue(latest.samples, 'jvm_gc_collection_seconds_sum').toFixed(1)}s)
                    </Typography>
                  </Box>
                </Box>
                <Box sx={{ mt: 1 }}>
                  <MetricsLineChart
                    height={110}
                    series={[{ data: gaugeSeries(history, 'jvm_threads_current'), label: 'threads' }]}
                  />
                </Box>
              </Paper>
            </Box>
          )}
        </>
      )}
    </Box>
  );
}
