import { useCallback, useMemo, useState } from 'react';
import Box from '@mui/material/Box';
import Paper from '@mui/material/Paper';
import Typography from '@mui/material/Typography';
import Chip from '@mui/material/Chip';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import Table from '@mui/material/Table';
import TableBody from '@mui/material/TableBody';
import TableCell from '@mui/material/TableCell';
import TableContainer from '@mui/material/TableContainer';
import TableHead from '@mui/material/TableHead';
import TableRow from '@mui/material/TableRow';
import RefreshIcon from '@mui/icons-material/Refresh';
import HubIcon from '@mui/icons-material/Hub';
import type { ConnectionParams } from '../hooks/useConnectionParams';
import { fetchGrpcStatus, type GrpcStatus, type ServingStatus } from '../lib/grpc';
import { useAutoRefresh } from '../hooks/useAutoRefresh';
import { humanizeError, type HumanError } from '../lib/errorMessage';
import { monospaceFontFamily } from '../theme';
import HumanErrorAlert from './HumanErrorAlert';

interface GrpcServicesPanelProps {
  connectionParams: ConnectionParams;
}

const POLL_INTERVAL_MS = 5000;

/** The "_default" / empty-name health key represents the whole-server status. */
const DEFAULT_HEALTH_KEYS = ['_default', ''];

function servingChipColor(status: ServingStatus | undefined): 'success' | 'error' | 'warning' | 'default' {
  switch (status) {
    case 'SERVING':
      return 'success';
    case 'NOT_SERVING':
      return 'error';
    case 'SERVICE_UNKNOWN':
    case 'UNKNOWN':
      return 'warning';
    default:
      return 'default';
  }
}

function streamingLabel(clientStreaming: boolean, serverStreaming: boolean): string {
  if (clientStreaming && serverStreaming) return 'bidi stream';
  if (serverStreaming) return 'server stream';
  if (clientStreaming) return 'client stream';
  return 'unary';
}

/**
 * Resolve the health status for a service. gRPC health keys are typically the
 * fully-qualified service name (e.g. `catalog.v1.CatalogService`), so match on
 * the exact service name first and fall back to undefined (rendered as "-").
 */
function healthFor(serviceName: string, health: Record<string, ServingStatus>): ServingStatus | undefined {
  return health[serviceName];
}

export default function GrpcServicesPanel({ connectionParams }: GrpcServicesPanelProps) {
  const [status, setStatus] = useState<GrpcStatus | null>(null);
  const [loadError, setLoadError] = useState<HumanError | null>(null);

  const loadStatus = useCallback(async (signal?: AbortSignal) => {
    try {
      const result = await fetchGrpcStatus(connectionParams, signal);
      setStatus(result);
      setLoadError(null);
    } catch (e) {
      if (signal?.aborted) return;
      setLoadError(humanizeError(e));
    }
  }, [connectionParams]);

  useAutoRefresh(loadStatus, { intervalMs: POLL_INTERVAL_MS });

  const refresh = useCallback(() => {
    void loadStatus();
  }, [loadStatus]);

  const services = useMemo(() => status?.services ?? [], [status]);
  const health = useMemo(() => status?.health ?? {}, [status]);

  const defaultHealth = useMemo<ServingStatus | undefined>(() => {
    for (const key of DEFAULT_HEALTH_KEYS) {
      if (health[key]) return health[key];
    }
    return undefined;
  }, [health]);

  const methodCount = useMemo(
    () => services.reduce((sum, s) => sum + (s.methods?.length ?? 0), 0),
    [services],
  );

  return (
    <Box sx={{ flex: 1, overflow: 'auto', p: 1.5 }}>
      {/* Header */}
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1.5 }}>
        <HubIcon fontSize="small" color="primary" />
        <Typography variant="subtitle1" sx={{ fontWeight: 700 }}>
          gRPC Services
        </Typography>
        <Chip
          size="small"
          label={`${services.length} service(s)`}
          color={services.length > 0 ? 'info' : 'default'}
          variant="outlined"
        />
        {methodCount > 0 && (
          <Chip size="small" label={`${methodCount} method(s)`} variant="outlined" />
        )}
        {defaultHealth && (
          <Tooltip title="Overall gRPC health-check serving status">
            <Chip
              size="small"
              label={`server ${defaultHealth}`}
              color={servingChipColor(defaultHealth)}
              variant="outlined"
            />
          </Tooltip>
        )}
        <Box sx={{ flex: 1 }} />
        <Tooltip title="Refresh now">
          <IconButton size="small" onClick={refresh} aria-label="Refresh gRPC services">
            <RefreshIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>

      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        Services and methods loaded from the gRPC protobuf descriptors, with each
        service&apos;s health-check serving status. Load descriptors via the{' '}
        <code>PUT /mockserver/grpc/descriptors</code> endpoint, or configure a
        descriptor / proto directory at startup.
      </Typography>

      {loadError && (
        <HumanErrorAlert
          error={loadError}
          sx={{ mb: 1.5 }}
        />
      )}

      {services.length === 0 ? (
        <Paper variant="outlined" sx={{ p: 1.25 }}>
          <Typography variant="body2" color="text.secondary" sx={{ p: 2, textAlign: 'center' }}>
            No gRPC services loaded. Upload a compiled protobuf FileDescriptorSet
            to <code>PUT /mockserver/grpc/descriptors</code> to mock gRPC methods.
          </Typography>
        </Paper>
      ) : (
        services.map((service) => {
          const serviceHealth = healthFor(service.name, health);
          return (
            <Paper key={service.name} variant="outlined" sx={{ p: 1.25, mb: 1.5 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
                <Typography variant="subtitle2" sx={{ fontWeight: 600, fontFamily: monospaceFontFamily }}>
                  {service.name}
                </Typography>
                <Chip
                  size="small"
                  label={serviceHealth ?? 'no health'}
                  color={servingChipColor(serviceHealth)}
                  variant="outlined"
                />
                <Box sx={{ flex: 1 }} />
                <Typography variant="caption" color="text.secondary">
                  {service.methods?.length ?? 0} method(s)
                </Typography>
              </Box>
              <TableContainer>
                <Table size="small">
                  <TableHead>
                    <TableRow>
                      <TableCell>Method</TableCell>
                      <TableCell>Input</TableCell>
                      <TableCell>Output</TableCell>
                      <TableCell align="right">Kind</TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {(service.methods ?? []).map((method) => (
                      <TableRow key={method.name}>
                        <TableCell>
                          <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }}>
                            {method.name}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }} color="text.secondary">
                            {method.inputType}
                          </Typography>
                        </TableCell>
                        <TableCell>
                          <Typography variant="caption" sx={{ fontFamily: monospaceFontFamily }} color="text.secondary">
                            {method.outputType}
                          </Typography>
                        </TableCell>
                        <TableCell align="right">
                          <Chip
                            size="small"
                            variant="outlined"
                            label={streamingLabel(method.clientStreaming, method.serverStreaming)}
                          />
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </TableContainer>
            </Paper>
          );
        })
      )}
    </Box>
  );
}
