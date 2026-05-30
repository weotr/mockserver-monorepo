import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import Alert from '@mui/material/Alert';
import AlertTitle from '@mui/material/AlertTitle';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import LinearProgress from '@mui/material/LinearProgress';
import CircularProgress from '@mui/material/CircularProgress';
import RefreshIcon from '@mui/icons-material/Refresh';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { useMetricsPolling } from '../hooks/useMetricsPolling';
import { findSample, metricValue, hasMetric } from '../lib/prometheusParser';
import { gaugeSeries, gaugeSeriesByLabel, ratePerSecond, latestRate } from '../lib/metricsDerive';
import { histogramQuantile } from '../lib/histogramQuantile';
import Sparkline from './Sparkline';
import MetricsLineChart from './MetricsLineChart';

const LATENCY_METRIC = 'mock_server_request_duration_seconds';

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
          {/* Summary stat cards */}
          <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))', gap: 1, mb: 1.5 }}>
            {SUMMARY.map(({ name, label }) => (
              <Paper key={name} variant="outlined" sx={{ p: 1.25 }}>
                <Typography variant="caption" color="text.secondary">{label}</Typography>
                <Typography variant="h5" sx={{ fontWeight: 700, lineHeight: 1.2 }}>
                  {metricValue(latest.samples, name).toLocaleString()}
                </Typography>
                <Sparkline data={gaugeSeries(history, name)} ariaLabel={`${label} over time`} />
              </Paper>
            ))}
          </Box>

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
              <Typography variant="caption" color="text.secondary">Request latency (since start)</Typography>
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
                    label: 'p95 ms',
                  }]}
                />
              </Box>
            </Paper>
          )}

          {/* JVM (only when the server exposes JVM metrics) */}
          {jvmEnabled && latest && (
            <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: 1, mb: 1.5 }}>
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

          {/* Per-action breakdown */}
          <Paper variant="outlined" sx={{ p: 1.25 }}>
            <Typography variant="caption" color="text.secondary">Actions executed</Typography>
            {maxAction === 0 ? (
              <Typography variant="body2" sx={{ mt: 0.5 }} color="text.secondary">
                No actions executed yet.
              </Typography>
            ) : (
              <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.75, mt: 0.75 }}>
                {actionRows.filter((r) => r.value > 0).map((r) => (
                  <Box key={r.name}>
                    <Box sx={{ display: 'flex', justifyContent: 'space-between' }}>
                      <Typography variant="body2" sx={{ textTransform: 'capitalize' }}>{r.label}</Typography>
                      <Typography variant="body2" sx={{ fontWeight: 600 }}>{r.value.toLocaleString()}</Typography>
                    </Box>
                    <LinearProgress
                      variant="determinate"
                      value={(r.value / maxAction) * 100}
                      sx={{ height: 6, borderRadius: 1 }}
                    />
                  </Box>
                ))}
              </Box>
            )}
          </Paper>
        </>
      )}
    </Box>
  );
}
