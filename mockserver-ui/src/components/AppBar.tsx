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
import DeleteSweepIcon from '@mui/icons-material/DeleteSweep';
import PauseIcon from '@mui/icons-material/Pause';
import PlayArrowIcon from '@mui/icons-material/PlayArrow';
import LayersClearIcon from '@mui/icons-material/LayersClear';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import DashboardIcon from '@mui/icons-material/Dashboard';
import TrafficIcon from '@mui/icons-material/Traffic';
import AccountTreeIcon from '@mui/icons-material/AccountTree';
import PostAddIcon from '@mui/icons-material/PostAdd';
import Inventory2Icon from '@mui/icons-material/Inventory2';
import SpeedIcon from '@mui/icons-material/Speed';
import BoltIcon from '@mui/icons-material/Bolt';
import CompareArrowsIcon from '@mui/icons-material/CompareArrows';
import Select from '@mui/material/Select';
import type { SelectChangeEvent } from '@mui/material/Select';
import Snackbar from '@mui/material/Snackbar';
import Alert from '@mui/material/Alert';
import BuildIcon from '@mui/icons-material/Build';
import SmartToyIcon from '@mui/icons-material/SmartToy';
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
import WsdlImportDialog from './WsdlImportDialog';
import OpenApiImportDialog from './OpenApiImportDialog';
import PactExportDialog from './PactExportDialog';

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
  const [wsdlOpen, setWsdlOpen] = useState(false);
  const [openApiOpen, setOpenApiOpen] = useState(false);
  const [pactOpen, setPactOpen] = useState(false);
  const [modeError, setModeError] = useState<string | null>(null);

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

  const handleModeChange = (event: SelectChangeEvent) => {
    const next = event.target.value as MockServerMode;
    const previous = mode;
    setModeState(next);
    void setServerMode(connectionParams, next)
      .then((r) => setModeState(r.mode))
      .catch((e) => {
        setModeState(previous); // revert on failure
        setModeError(e instanceof Error ? e.message : 'Failed to change mode');
      });
  };

  return (
    <MuiAppBar position="static" elevation={0} sx={{ borderBottom: 1, borderColor: 'divider' }}>
      <Toolbar variant="dense" sx={{ gap: 1, minHeight: 36 }}>
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
          <ToggleButton value="composer" aria-label="Compose new expectations">
            <PostAddIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Composer
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
          <ToggleButton value="metrics" aria-label="Metrics view">
            <SpeedIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            Metrics
          </ToggleButton>
          <ToggleButton value="mcp-tools" aria-label="MCP tools view">
            <SmartToyIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} />
            MCP
          </ToggleButton>
        </ToggleButtonGroup>
        <Box sx={{ flex: 1 }} />
        <Typography variant="caption" color="text.secondary" sx={{ display: { xs: 'none', md: 'block' } }}>
          ⌘K search · ⌘L clear · Esc filter
        </Typography>
        <Tooltip title={autoScroll ? 'Pause auto-scroll' : 'Resume auto-scroll'}>
          <IconButton size="small" color="inherit" onClick={toggleAutoScroll}>
            {autoScroll ? <PauseIcon fontSize="small" /> : <PlayArrowIcon fontSize="small" />}
          </IconButton>
        </Tooltip>
        <Tooltip title={`Switch to ${themeMode === 'dark' ? 'light' : 'dark'} mode`}>
          <IconButton size="small" color="inherit" onClick={toggleTheme}>
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
              void onClearExpectations();
              setAnchorEl(null);
            }}
          >
            <ListItemIcon><LayersClearIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Clear server expectations</ListItemText>
          </MenuItem>
          <MenuItem
            onClick={() => {
              void onClearServer();
              setAnchorEl(null);
            }}
          >
            <ListItemIcon><RestartAltIcon fontSize="small" /></ListItemIcon>
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
            <ListItemText>Export Pact…</ListItemText>
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
    </MuiAppBar>
  );
}
