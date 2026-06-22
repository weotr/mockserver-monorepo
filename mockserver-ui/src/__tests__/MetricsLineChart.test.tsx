import { describe, it, expect, afterEach } from 'vitest';
import { render, cleanup } from '@testing-library/react';
import MetricsLineChart, { formatTimeLabel } from '../components/MetricsLineChart';

afterEach(cleanup);

describe('MetricsLineChart', () => {
  it('shows a placeholder when there are fewer than two points', () => {
    const { getByText } = render(<MetricsLineChart series={[{ data: [5], label: 'x' }]} />);
    expect(getByText('collecting…')).toBeInTheDocument();
  });

  it('renders an SVG chart for two or more points (real @mui/x-charts render)', () => {
    const { container } = render(
      <MetricsLineChart series={[{ data: [1, 2, 3], label: 'x' }]} valueFormatter={(v) => `${v}`} />,
    );
    expect(container.querySelector('svg')).not.toBeNull();
  });

  it('renders a filled area under the line for a single series', () => {
    const { container } = render(
      <MetricsLineChart series={[{ data: [1, 2, 3], label: 'x' }]} valueFormatter={(v) => `${v}`} />,
    );
    // @mui/x-charts renders the area fill under the line as the area plot path.
    expect(container.querySelector('.MuiLineChart-area')).not.toBeNull();
  });

  it('does not fill the area when there are multiple series (keeps clean lines)', () => {
    const { container } = render(
      <MetricsLineChart
        series={[
          { data: [1, 2, 3], label: 'a' },
          { data: [3, 2, 1], label: 'b' },
        ]}
      />,
    );
    expect(container.querySelector('.MuiLineChart-area')).toBeNull();
  });

  it('renders a bottom (time) x-axis when timestamps are supplied', () => {
    const t0 = new Date('2024-01-01T09:05:00').getTime();
    const t1 = new Date('2024-01-01T09:06:00').getTime();
    const { container } = render(
      <MetricsLineChart series={[{ data: [1, 2], label: 'x' }]} timestamps={[t0, t1]} />,
    );
    expect(container.querySelector('.MuiChartsAxis-directionX')).not.toBeNull();
  });

  it('formats epoch-millis timestamps as readable HH:MM labels', () => {
    const noon = new Date('2024-01-01T12:30:00').getTime();
    // Locale-formatted HH:MM — assert it contains the minutes and is short.
    const label = formatTimeLabel(noon);
    expect(label).toMatch(/\b30\b/);
    expect(label.length).toBeLessThanOrEqual(8);
  });
});
