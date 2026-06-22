import { LineChart } from '@mui/x-charts/LineChart';
import Box from '@mui/material/Box';
import Typography from '@mui/material/Typography';
import { useTheme } from '@mui/material/styles';

export interface MetricsSeries {
  data: number[];
  label: string;
}

interface MetricsLineChartProps {
  series: MetricsSeries[];
  height?: number;
  /** Formats y-axis + tooltip values (e.g. bytes → "1.2 MB"). */
  valueFormatter?: (value: number) => string;
  /**
   * Epoch-millis timestamp for each point (one per sample, in lockstep with the
   * series data). When supplied the x-axis renders readable wall-clock time
   * labels (HH:MM) instead of bare indices; falls back to indices if omitted or
   * length-mismatched.
   */
  timestamps?: number[];
}

/** HH:MM in the viewer's locale, used for the time x-axis tick labels. */
// eslint-disable-next-line react-refresh/only-export-components
export function formatTimeLabel(epochMillis: number): string {
  return new Date(epochMillis).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/**
 * Thin wrapper around `@mui/x-charts` LineChart for the Metrics view. Renders a
 * real time x-axis (HH:MM tick labels from the snapshot timestamps), a soft area
 * fill under each line and a coherent series colour drawn from the theme palette
 * so the charts read as intentional data-viz. Disables point marks for a clean
 * live line, and shows a "collecting…" placeholder until at least two samples
 * exist.
 */
export default function MetricsLineChart({ series, height = 220, valueFormatter, timestamps }: MetricsLineChartProps) {
  const theme = useTheme();
  // shortest series length, so an accidental ragged input shows the placeholder
  // rather than silently clipping points.
  const length = series.length === 0 ? 0 : Math.min(...series.map((s) => s.data.length));

  if (length < 2) {
    return (
      <Box sx={{ height, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <Typography variant="caption" color="text.secondary">
          collecting…
        </Typography>
      </Box>
    );
  }

  // Coherent palette: lead with the primary/secondary brand colours, then the
  // charting palette, so single-series charts read as one intentional accent
  // rather than an arbitrary default colour.
  const palette = [
    theme.palette.primary.main,
    theme.palette.secondary.main,
    theme.palette.info.main,
    theme.palette.success.main,
    theme.palette.warning.main,
    theme.palette.error.main,
  ];

  // Use real timestamps when they line up with the data; otherwise fall back to
  // plain indices (keeps the component robust if a caller omits timestamps).
  const useTime = Array.isArray(timestamps) && timestamps.length === length;
  const xData = useTime
    ? (timestamps as number[]).slice(0, length)
    : Array.from({ length }, (_, i) => i);

  // A single-series chart fills the area under the line for a stronger data-viz
  // read; multi-series charts keep clean lines so overlapping fills don't muddy.
  const fillArea = series.length === 1;

  return (
    <LineChart
      height={height}
      series={series.map((s, i) => ({
        data: s.data,
        label: s.label,
        showMark: false,
        area: fillArea,
        curve: 'monotoneX' as const,
        color: palette[i % palette.length],
        valueFormatter: valueFormatter ? (v: number | null) => (v == null ? '' : valueFormatter(v)) : undefined,
      }))}
      xAxis={[{
        data: xData,
        scaleType: 'point',
        valueFormatter: useTime
          ? (value: number) => formatTimeLabel(value)
          : () => '',
      }]}
      yAxis={[{ valueFormatter: valueFormatter ? (v: number) => valueFormatter(v) : undefined }]}
      margin={{ left: 56, right: 12, top: 16, bottom: useTime ? 24 : 8 }}
      hideLegend={series.length <= 1}
      sx={{
        // Soften the filled area so it reads as a gradient-style wash under the
        // line rather than a solid block.
        '& .MuiLineChart-area, & .MuiAreaElement-root': {
          fillOpacity: 0.16,
        },
        '& .MuiChartsAxis-tickLabel': {
          fontSize: theme.typography.caption.fontSize,
        },
      }}
    />
  );
}
