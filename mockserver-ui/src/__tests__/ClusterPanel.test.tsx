import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, waitFor, cleanup, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import ClusterPanel from '../components/ClusterPanel';
import type { ClusterInfo } from '../lib/cluster';

const params = { host: '127.0.0.1', port: '1080', secure: false };

function renderPanel() {
  return render(
    <ThemeProvider theme={buildTheme('dark')}>
      <ClusterPanel connectionParams={params} />
    </ThemeProvider>,
  );
}

function stubFetchCluster(response: ClusterInfo) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => response,
    })),
  );
}

const singleNode: ClusterInfo = {
  clustered: false,
  nodeId: 'node-local',
  coordinator: 'node-local',
  memberCount: 1,
  members: [{ id: 'node-local', coordinator: true, local: true }],
};

const multiNode: ClusterInfo = {
  clustered: true,
  nodeId: 'node-2',
  coordinator: 'node-1',
  clusterName: 'mockserver-cluster',
  memberCount: 3,
  members: [
    { id: 'node-1', coordinator: true, local: false },
    { id: 'node-2', coordinator: false, local: true },
    { id: 'node-3', coordinator: false, local: false },
  ],
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('ClusterPanel', () => {
  it('renders title and description', () => {
    stubFetchCluster(singleNode);
    renderPanel();
    expect(screen.getByText('Cluster')).toBeInTheDocument();
    expect(screen.getByText(/state-backend cluster this MockServer node participates in/)).toBeInTheDocument();
  });

  it('handles the single-node (non-clustered) case', async () => {
    stubFetchCluster(singleNode);
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('Single node')).toBeInTheDocument();
    });
    expect(screen.getByText('1 member')).toBeInTheDocument();
    // node + coordinator ids render (in both the header summary and the member row)
    expect(screen.getAllByText('node-local').length).toBeGreaterThan(0);

    // the single member row renders as the local coordinator
    const table = screen.getByRole('table');
    expect(within(table).getByText('Coordinator')).toBeInTheDocument();
    expect(within(table).getByText('Local')).toBeInTheDocument();
  });

  it('renders multiple members from a clustered response', async () => {
    stubFetchCluster(multiNode);
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('Clustered')).toBeInTheDocument();
    });
    expect(screen.getByText('3 members')).toBeInTheDocument();
    expect(screen.getByText('mockserver-cluster')).toBeInTheDocument();

    // every member id appears in the table (node-1 is also the coordinator in the header)
    const table = screen.getByRole('table');
    expect(within(table).getByText('node-1')).toBeInTheDocument();
    expect(within(table).getByText('node-2')).toBeInTheDocument();
    expect(within(table).getByText('node-3')).toBeInTheDocument();
    // exactly one coordinator and one local node across the three members
    expect(within(table).getByText('Coordinator')).toBeInTheDocument();
    expect(within(table).getByText('Local')).toBeInTheDocument();
    expect(within(table).getAllByText('Member')).toHaveLength(2);
  });

  it('shows an error alert when the fetch fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('Connection refused')));
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('Could not load cluster status')).toBeInTheDocument();
    });
    expect(screen.getByText(/Couldn’t reach the MockServer/)).toBeInTheDocument();
  });

  it('shows the "not available" branch on a 404 (older server)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 404,
        statusText: 'Not Found',
        json: async () => ({}),
      })),
    );
    renderPanel();

    await waitFor(() => {
      expect(screen.getByText('Cluster status not available')).toBeInTheDocument();
    });
    expect(screen.getByText(/does not expose cluster status/)).toBeInTheDocument();
  });

  it('has a Refresh button that triggers a new poll', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: true,
      status: 200,
      json: async () => singleNode,
    }));
    vi.stubGlobal('fetch', fetchMock);

    const user = userEvent.setup();
    renderPanel();

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalled();
    });
    const callsBefore = fetchMock.mock.calls.length;

    await user.click(screen.getByRole('button', { name: 'Refresh cluster' }));

    await waitFor(() => {
      expect(fetchMock.mock.calls.length).toBeGreaterThan(callsBefore);
    });
  });
});
