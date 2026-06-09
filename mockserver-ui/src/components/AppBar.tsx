import MuiAppBar from '@mui/material/AppBar';
import Toolbar from '@mui/material/Toolbar';
import Typography from '@mui/material/Typography';
import IconButton from '@mui/material/IconButton';
import Tooltip from '@mui/material/Tooltip';
import Chip from '@mui/material/Chip';
import Menu from '@mui/material/Menu';
import MenuItem from '@mui/material/MenuItem';
import ListItemIcon from '@mui/material/ListItemIcon';
import ListItemText from '@mui/material/ListItemText';
import ToggleButtonGroup from '@mui/material/ToggleButtonGroup';
import ToggleButton from '@mui/material/ToggleButton';
import Box from '@mui/material/Box';
import DarkModeIcon from '@mui/icons-material/DarkMode';
import LightModeIcon from '@mui/icons-material/LightMode';
import AccessTimeIcon from '@mui/icons-material/AccessTime';
import SettingsIcon from '@mui/icons-material/Settings';
import TroubleshootIcon from '@mui/icons-material/Troubleshoot';
import ClockDialog from './ClockDialog';
import ConfigurationDialog from './ConfigurationDialog';
import ExplainUnmatchedDialog from './ExplainUnmatchedDialog';
import DeleteSweepIcon from '@mui/icons-material/DeleteSweep';
import PauseIcon from '@mui/icons-material/Pause';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import LayersClearIcon from '@mui/icons-material/LayersClear';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import RocketLaunchIcon from '@mui/icons-material/RocketLaunch';
import DashboardIcon from '@mui/icons-material/Dashboard';
import TrafficIcon from '@mui/icons-material/Traffic';
import AccountTreeIcon from '@mui/icons-material/AccountTree';
import PostAddIcon from '@mui/icons-material/PostAdd';
import Inventory2Icon from '@mui/icons-material/Inventory2';
import SpeedIcon from '@mui/icons-material/Speed';
import BoltIcon from '@mui/icons-material/Bolt';
import CompareArrowsIcon from '@mui/icons-material/CompareArrows';
import PlaylistAddCheckIcon from '@mui/icons-material/PlaylistAddCheck';
import PanToolIcon from '@mui/icons-material/PanTool';
import Select from '@mui/material/Select';
import type { SelectChangeEvent } from '@mui/material/Select';
import Snackbar from '@mui/material/Snackbar';
import Alert from '@mui/material/Alert';
import BuildIcon from '@mui/icons-material/Build';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import DownloadIcon from '@mui/icons-material/Download';
import { useState, useEffect } from 'react';
import { useDashboardStore, type ViewMode } from '../store';
import type { ConnectionStatus } from '../types';
import { useConnectionParams } from '../hooks/useConnectionParams';
import {
  fetchMode,
  setMode as setServerMode,
  MOCK_SERVER_MODES,
  MODE_DESCRIPTIONS,
  type MockServerMode,
} from '../lib/mockServerMode';
import { fetchHttp3Status, type Http3Status } from '../lib/http3Status';
import WsdlImportDialog from './WsdlImportDialog';
import OpenApiImportDialog from './OpenApiImportDialog';
import PactExportDialog from './PactExportDialog';
import OidcDialog from './OidcDialog';
import AsyncApiDialog from './AsyncApiDialog';
import CrudDialog from './CrudDialog';
import FileStoreDialog from './FileStoreDialog';
import DiffRequestsDialog from './DiffRequestsDialog';
import ConfirmDialog from './ConfirmDialog';
import VpnKeyIcon from '@mui/icons-material/VpnKey';
import HubIcon from '@mui/icons-material/Hub';
import StorageIcon from '@mui/icons-material/Storage';
import FolderOpenIcon from '@mui/icons-material/FolderOpen';
import Divider from '@mui/material/Divider';

function statusColor(status: ConnectionStatus): 'success' | 'warning' | 'error' | 'default' {
  switch (status) {
    case 'connected':
      return 'success';
    case 'connecting':
      return 'warning';
    case 'error':
      return 'error';
    default:
      return 'default';
  }
}

/**
 * MUI's default outlined-chip colours (`success.main`, `error.main`, …) are
 * dark enough to disappear against the primary-coloured AppBar background in
 * light mode. Override with pale tints of the same hue in light mode only;
 * in dark mode the defaults already contrast against the deep-blue bar so we
 * leave them alone.
 */
function statusChipPaletteSx(themeMode: 'light' | 'dark', status: ConnectionStatus): Record<string, unknown> {
  if (themeMode === 'dark') return {};
  const tints: Record<ConnectionStatus, string> = {
    connected: '#7fffa0',    // pale green
    connecting: '#ffd180',   // pale amber
    error: '#ff8a80',        // pale red
    disconnected: 'rgba(255,255,255,0.85)',
  };
  const tint = tints[status] ?? 'rgba(255,255,255,0.85)';
  return {
    color: tint,
    borderColor: tint,
    '& .MuiChip-label': { color: tint },
  };
}

interface AppBarProps {
  onClearServer: () => Promise<void>;
  onClearLogs: () => Promise<void>;
  onClearExpectations: () => Promise<void>;
}

export default function AppBar({ onClearServer, onClearLogs, onClearExpectations }: AppBarProps) {
  const connectionStatus = useDashboardStore((s) => s.connectionStatus);
  const themeMode = useDashboardStore((s) => s.themeMode);
  const toggleTheme = useDashboardStore((s) => s.toggleThemeMode);
  const autoScroll = useDashboardStore((s) => s.autoScroll);
  const toggleAutoScroll = useDashboardStore((s) => s.toggleAutoScroll);
  const view = useDashboardStore((s) => s.view);
  const setView = useDashboardStore((s) => s.setView);
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null);

  const connectionParams = useConnectionParams();
  const [mode, setModeState] = useState<MockServerMode | null>(null);
  const [toolsAnchorEl, setToolsAnchorEl] = useState<null | HTMLElement>(null);
  const [clockOpen, setClockOpen] = useState(false);
  const [configOpen, setConfigOpen] = useState(false);
  const [explainOpen, setExplainOpen] = useState(false);
  const [oidcOpen, setOidcOpen] = useState(false);
  const [asyncApiOpen, setAsyncApiOpen] = useState(false);
  const [wsdlOpen, setWsdlOpen] = useState(false);
  const [openApiOpen, setOpenApiOpen] = useState(false);
  const [pactOpen, setPactOpen] = useState(false);
  const [crudOpen, setCrudOpen] = useState(false);
  const [fileStoreOpen, setFileStoreOpen] = useState(false);
  const [diffOpen, setDiffOpen] = useState(false);
  const [modeError, setModeError] = useState<string | null>(null);
  const [http3Status, setHttp3Status] = useState<Http3Status | null>(null);
  const setNotification = useDashboardStore((s) => s.setNotification);
  // Confirmation for destructive actions (reset / bulk clear). Holds the pending action.
  const [confirm, setConfirm] = useState<{ title: string; message: string; confirmLabel: string; onConfirm: () => void } | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    void fetchMode(connectionParams, controller.signal)
      .then((r) => {
        if (!controller.signal.aborted) setModeState(r.mode);
      })
      .catch(() => {
        /* mode endpoint unavailable (older server) — hide the control */
      });
    return () => controller.abort();
  }, [connectionParams]);

  useEffect(() => {
    const controller = new AbortController();
    const poll = () => {
      void fetchHttp3Status(connectionParams, controller.signal)
        .then((status) => {
          if (!controller.signal.aborted) setHttp3Status(status);
        })
        .catch(() => {
          /* endpoint unavailable (older server or H3 not compiled in) */
        });
    };
    poll();
    // poll every 5 seconds so active connection count stays reasonably fresh
    const interval = setInterval(poll, 5000);
    return () => {
      controller.abort();
      clearInterval(interval);
    };
  }, [connectionParams]);

  const handleModeChange = (event: SelectChangeEvent) => {
    const next = event.target.value as MockServerMode;
    const previous = mode;
    setModeState(next);
    void setServerMode(connectionParams, next)
      .then((r) => {
        setModeState(r.mode);
        setNotification({ message: `Operating mode set to ${r.mode}`, severity: 'success' });
      })
      .catch((e) => {
        setModeState(previous); // revert on failure
        setModeError(e instanceof Error ? e.message : 'Failed to change mode');
      });
  };

  return (
    <MuiAppBar position="static" elevation={0} sx={{ borderBottom: 1, borderColor: 'divider' }}>
      <Toolbar variant="dense" sx={{ gap: 1, minHeight: 36, flexWrap: 'wrap', rowGap: 0.5, py: 0.5 }}>
        <Typography variant="h6" sx={{ fontWeight: 700, fontSize: '0.9rem' }}>
          MockServer
        </Typography>
        <Chip
          label={connectionStatus}
          size="small"
          color={statusColor(connectionStatus)}
          variant="outlined"
          sx={{
            textTransform: 'capitalize',
            ...statusChipPaletteSx(themeMode, connectionStatus),
          }}
        />
        {http3Status?.enabled && (
          <Tooltip title={`HTTP/3 (QUIC) on UDP port ${http3Status.port} -- ${http3Status.activeConnections} active connection${http3Status.activeConnections === 1 ? '' : 's'}`}>
            <Chip
              label={`H3 :${http3Status.port} (${http3Status.activeConnections})`}
              size="small"
              color="info"
              variant="outlined"
              sx={{
                fontSize: '0.7rem',
                ...(themeMode === 'light' ? {
                  color: 'rgba(255,255,255,0.9)',
                  borderColor: 'rgba(255,255,255,0.4)',
                  '& .MuiChip-label': { color: 'rgba(255,255,255,0.9)' },
                } : {}),
              }}
            />
          </Tooltip>
        )}
        <ToggleButtonGroup
          value={view}
          exclusive
          size="small"
          onChange={(_, newView: ViewMode | null) => {
            if (newView !== null) setView(newView);
          }}
          sx={{
            ml: 1,
            '& .MuiToggleButton-root': {
              py: 0.25,
              px: 1,
              fontSize: '0.7rem',
              textTransform: 'none',
              lineHeight: 1.4,
              // Light-mode-only: force white text + translucent border so the
              // buttons read against the primary-coloured AppBar. Dark mode
              // keeps MUI's defaults which already contrast against the bar.
              ...(themeMode === 'light' ? {
                color: 'primary.contrastText',
                borderColor: 'rgba(255, 255, 255, 0.3)',
                '&:hover': {
                  backgroundColor: 'rgba(255, 255, 255, 0.08)',
                },
                '&.Mui-selected': {
                  color: 'primary.contrastText',
                  backgroundColor: 'rgba(255, 255, 255, 0.18)',
                  '&:hover': {
                    backgroundColor: 'rgba(255, 255, 255, 0.24)',
                  },
                },
              } : {}),
            },
          }}
        >
          <ToggleButton value="get-started" aria-label="Get started view">
            <RocketLaunchIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Get Started
          </ToggleButton>
          <ToggleButton value="dashboard" aria-label="Dashboard view">
            <DashboardIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Dashboard
          </ToggleButton>
          <ToggleButton value="traffic" aria-label="Traffic inspector view">
            <TrafficIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Traffic
          </ToggleButton>
          <ToggleButton value="sessions" aria-label="Session inspector view">
            <AccountTreeIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Sessions
          </ToggleButton>
          <ToggleButton value="composer" aria-label="Mocks view">
            <PostAddIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Mocks
          </ToggleButton>
          <ToggleButton value="library" aria-label="Library of captured content">
            <Inventory2Icon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Library
          </ToggleButton>
          <ToggleButton value="chaos" aria-label="Service chaos view">
            <BoltIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Chaos
          </ToggleButton>
          <ToggleButton value="drift" aria-label="Drift detection view">
            <CompareArrowsIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Drift
          </ToggleButton>
          <ToggleButton value="verification" aria-label="Verification view">
            <PlaylistAddCheckIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Verify
          </ToggleButton>
          <ToggleButton value="async" aria-label="AsyncAPI broker mock view">
            <HubIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Async
          </ToggleButton>
          <ToggleButton value="breakpoints" aria-label="Breakpoints view">
            <PanToolIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Breakpoints
          </ToggleButton>
          <ToggleButton value="metrics" aria-label="Metrics view">
            <SpeedIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Metrics
          </ToggleButton>
        </ToggleButtonGroup>
        <Box sx={{ flex: 1 }} />
        <Typography variant="caption" color="text.secondary" sx={{ display: { xs: 'none', md: 'block' } }}>
          ⌘K search · ⌘L clear logs · Esc filter
        </Typography>
        <Tooltip title="Server clock (freeze / advance time)">
          <IconButton size="small" color="inherit" onClick={() => setClockOpen(true)} aria-label="Server clock">
            <AccessTimeIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title="Explain unmatched requests">
          <IconButton size="small" color="inherit" onClick={() => setExplainOpen(true)} aria-label="Explain unmatched requests">
            <TroubleshootIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title="Server configuration">
          <IconButton size="small" color="inherit" onClick={() => setConfigOpen(true)} aria-label="Server configuration">
            <SettingsIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title={autoScroll ? 'Pause auto-scroll' : 'Resume auto-scroll'}>
          <IconButton size="small" color="inherit" onClick={toggleAutoScroll} aria-label={autoScroll ? 'Pause auto-scroll' : 'Resume auto-scroll'}>
            {autoScroll ? <PauseIcon fontSize="small" /> : <PlayArrowIcon fontSize="small" />}
          </IconButton>
        </Tooltip>
        <Tooltip title={`Switch to ${themeMode === 'dark' ? 'light' : 'dark'} mode`}>
          <IconButton size="small" color="inherit" onClick={toggleTheme} aria-label={`Switch to ${themeMode === 'dark' ? 'light' : 'dark'} mode`}>
            {themeMode === 'dark' ? <LightModeIcon fontSize="small" /> : <DarkModeIcon fontSize="small" />}
          </IconButton>
        </Tooltip>
        {mode !== null && (
          <Tooltip title={MODE_DESCRIPTIONS[mode]}>
            <Select
              value={mode}
              onChange={handleModeChange}
              size="small"
              aria-label="Operating mode"
              sx={{
                color: 'inherit',
                fontSize: '0.7rem',
                height: 28,
                '.MuiOutlinedInput-notchedOutline': { borderColor: 'rgba(255, 255, 255, 0.3)' },
                '.MuiSvgIcon-root': { color: 'inherit' },
              }}
            >
              {MOCK_SERVER_MODES.map((m) => (
                <MenuItem key={m} value={m} sx={{ fontSize: '0.8rem' }}>
                  {m}
                </MenuItem>
              ))}
            </Select>
          </Tooltip>
        )}
        <Tooltip title="Import / export">
          <IconButton
            size="small"
            color="inherit"
            aria-label="Import / export tools"
            onClick={(e) => setToolsAnchorEl(e.currentTarget)}
          >
            <BuildIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title="Clear">
          <IconButton
            size="small"
            color="inherit"
            onClick={(e) => setAnchorEl(e.currentTarget)}
          >
            <DeleteSweepIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Menu
          anchorEl={anchorEl}
          open={Boolean(anchorEl)}
          onClose={() => setAnchorEl(null)}
        >
          <MenuItem
            onClick={() => {
              void onClearLogs();
              setAnchorEl(null);
            }}
          >
            <ListItemIcon><LayersClearIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Clear server logs</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setAnchorEl(null);
              setConfirm({
                title: 'Clear all expectations?',
                message: 'This removes every registered expectation from the server. Recorded requests and logs are kept. This cannot be undone.',
                confirmLabel: 'Clear expectations',
                onConfirm: () => { void onClearExpectations(); },
              });
            }}
          >
            <ListItemIcon><LayersClearIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Clear server expectations</ListItemText>
          </MenuItem>
          <Divider />
          <MenuItem
            onClick={() => {
              setAnchorEl(null);
              setConfirm({
                title: 'Reset the entire server?',
                message: 'This clears ALL expectations, recorded requests and logs, and resets server state. This cannot be undone.',
                confirmLabel: 'Reset server',
                onConfirm: () => { void onClearServer(); },
              });
            }}
            sx={{ color: 'error.main' }}
          >
            <ListItemIcon><RestartAltIcon fontSize="small" color="error" /></ListItemIcon>
            <ListItemText>Reset server (all)</ListItemText>
          </MenuItem>
        </Menu>
        <Menu
          anchorEl={toolsAnchorEl}
          open={Boolean(toolsAnchorEl)}
          onClose={() => setToolsAnchorEl(null)}
        >
          <MenuItem
            onClick={() => {
              setOpenApiOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><UploadFileIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Import OpenAPI…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setWsdlOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><UploadFileIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Import WSDL…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setPactOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><DownloadIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Pact contract (export / verify)…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setOidcOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><VpnKeyIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Mock OIDC provider…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setAsyncApiOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><HubIcon fontSize="small" /></ListItemIcon>
            <ListItemText>AsyncAPI broker mock…</ListItemText>
          </MenuItem>
          <Divider />
          <MenuItem
            onClick={() => {
              setCrudOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><StorageIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Register CRUD resource…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setFileStoreOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><FolderOpenIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Mock file store…</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              setDiffOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><CompareArrowsIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Diff two requests…</ListItemText>
          </MenuItem>
        </Menu>
        <OpenApiImportDialog
          open={openApiOpen}
          onClose={() => setOpenApiOpen(false)}
          connectionParams={connectionParams}
        />
        <WsdlImportDialog
          open={wsdlOpen}
          onClose={() => setWsdlOpen(false)}
          connectionParams={connectionParams}
        />
        <PactExportDialog
          open={pactOpen}
          onClose={() => setPactOpen(false)}
          connectionParams={connectionParams}
        />
        <Snackbar
          open={modeError !== null}
          autoHideDuration={4000}
          onClose={() => setModeError(null)}
        >
          <Alert severity="error" onClose={() => setModeError(null)} sx={{ width: '100%' }}>
            {modeError}
          </Alert>
        </Snackbar>
      </Toolbar>
      <ClockDialog open={clockOpen} onClose={() => setClockOpen(false)} connectionParams={connectionParams} />
      <ConfigurationDialog open={configOpen} onClose={() => setConfigOpen(false)} connectionParams={connectionParams} />
      <ExplainUnmatchedDialog open={explainOpen} onClose={() => setExplainOpen(false)} connectionParams={connectionParams} />
      <OidcDialog open={oidcOpen} onClose={() => setOidcOpen(false)} connectionParams={connectionParams} />
      <AsyncApiDialog open={asyncApiOpen} onClose={() => setAsyncApiOpen(false)} connectionParams={connectionParams} />
      <CrudDialog open={crudOpen} onClose={() => setCrudOpen(false)} connectionParams={connectionParams} />
      <FileStoreDialog open={fileStoreOpen} onClose={() => setFileStoreOpen(false)} connectionParams={connectionParams} />
      <DiffRequestsDialog open={diffOpen} onClose={() => setDiffOpen(false)} connectionParams={connectionParams} />
      <ConfirmDialog
        open={confirm !== null}
        title={confirm?.title ?? ''}
        message={confirm?.message ?? ''}
        confirmLabel={confirm?.confirmLabel ?? 'Confirm'}
        onConfirm={() => confirm?.onConfirm()}
        onClose={() => setConfirm(null)}
      />
    </MuiAppBar>
  );
}
