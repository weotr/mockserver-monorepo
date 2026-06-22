import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import LogPanel from '../components/LogPanel';
import { useDashboardStore } from '../store';

describe('LogPanel', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      logMessages: [],
      logSearch: '',
      autoScroll: true,
      logShowForwarded: true,
    });
  });

  it('shows empty state when no log messages', () => {
    render(<LogPanel />);
    expect(screen.getByText(/No log messages/)).toBeInTheDocument();
  });

  it('renders log entries', () => {
    useDashboardStore.setState({
      logMessages: [
        { key: 'log1', value: { messageParts: [{ key: 'msg', value: 'test log entry' }] } },
      ],
    });
    render(<LogPanel />);
    expect(screen.getByText('test log entry')).toBeInTheDocument();
  });

  it('filters log messages by search term', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      logMessages: [
        { key: 'log1', value: { messageParts: [{ key: 'msg1', value: 'error occurred' }] } },
        { key: 'log2', value: { messageParts: [{ key: 'msg2', value: 'request received' }] } },
      ],
    });

    render(<LogPanel />);

    const searchInput = screen.getByPlaceholderText('Search...');
    await user.type(searchInput, 'error');

    expect(screen.getByText('error occurred')).toBeInTheDocument();
    expect(screen.queryByText('request received')).not.toBeInTheDocument();
  });

  it('shows "no matching" message when search matches nothing', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      logMessages: [
        { key: 'log1', value: { messageParts: [{ key: 'msg1', value: 'test entry' }] } },
      ],
    });

    render(<LogPanel />);
    const searchInput = screen.getByPlaceholderText('Search...');
    await user.type(searchInput, 'xyz-nonexistent');

    expect(screen.getByText('No matching log messages')).toBeInTheDocument();
  });

  it('hides forwarded request entries when "Show forwarded" is off', () => {
    useDashboardStore.setState({
      logShowForwarded: false,
      logMessages: [
        {
          key: 'fwd',
          value: {
            style: { color: 'rgb(152, 208, 255)' },
            messageParts: [{ key: 'm', value: 'forwarded entry' }],
          },
        },
        { key: 'normal', value: { messageParts: [{ key: 'm', value: 'received entry' }] } },
      ],
    });

    render(<LogPanel />);
    expect(screen.queryByText('forwarded entry')).not.toBeInTheDocument();
    expect(screen.getByText('received entry')).toBeInTheDocument();
  });

  it('shows forwarded entries when "Show forwarded" is on', () => {
    useDashboardStore.setState({
      logShowForwarded: true,
      logMessages: [
        {
          key: 'fwd',
          value: {
            style: { color: 'rgb(152, 208, 255)' },
            messageParts: [{ key: 'm', value: 'forwarded entry' }],
          },
        },
      ],
    });

    render(<LogPanel />);
    expect(screen.getByText('forwarded entry')).toBeInTheDocument();
  });

  it('renders log groups', () => {
    useDashboardStore.setState({
      logMessages: [
        {
          key: 'group1_log_group',
          group: {
            key: 'group1_summary',
            value: { messageParts: [{ key: 'summary', value: 'group summary' }] },
          },
          value: [
            { key: 'child1', value: { messageParts: [{ key: 'c1', value: 'child entry' }] } },
          ],
        },
      ],
    });

    render(<LogPanel />);
    expect(screen.getByText('group summary')).toBeInTheDocument();
  });
});
