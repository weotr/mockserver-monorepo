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
import Button from '@mui/material/Button';
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
import DownloadIcon from '@mui/icons-material/Download';
import ChatIcon from '@mui/icons-material/Chat';
import { useState, useCallback } from 'react';
import { useDashboardStore, type ViewMode } from '../store';
import ConversationWizard from './ConversationWizard';
import type { ConnectionStatus } from '../types';
import type { ConnectionParams } from '../hooks/useConnectionParams';

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

interface AppBarProps {
  onClearServer: () => Promise<void>;
  onClearLogs: () => Promise<void>;
  onClearExpectations: () => Promise<void>;
  connectionParams: ConnectionParams;
}

export default function AppBar({ onClearServer, onClearLogs, onClearExpectations, connectionParams }: AppBarProps) {
  const connectionStatus = useDashboardStore((s) => s.connectionStatus);
  const themeMode = useDashboardStore((s) => s.themeMode);
  const toggleTheme = useDashboardStore((s) => s.toggleThemeMode);
  const autoScroll = useDashboardStore((s) => s.autoScroll);
  const toggleAutoScroll = useDashboardStore((s) => s.toggleAutoScroll);
  const view = useDashboardStore((s) => s.view);
  const setView = useDashboardStore((s) => s.setView);
  const [anchorEl, setAnchorEl] = useState<null | HTMLElement>(null);
  const [downloading, setDownloading] = useState(false);
  const [wizardOpen, setWizardOpen] = useState(false);

  const handleDownloadHar = useCallback(async () => {
    setDownloading(true);
    try {
      const protocol = connectionParams.secure ? 'https' : 'http';
      const base = `${protocol}://${connectionParams.host}:${connectionParams.port}`;
      const url = `${base}/mockserver/retrieve?type=REQUEST_RESPONSES&format=HAR`;
      const response = await fetch(url, { method: 'PUT' });
      if (!response.ok) {
        console.error(`HAR download failed: ${response.status} ${response.statusText}`);
        return;
      }
      const blob = await response.blob();
      const objectUrl = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = 'mockserver-traffic.har';
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      URL.revokeObjectURL(objectUrl);
    } catch (err) {
      console.error('HAR download failed:', err);
    } finally {
      setDownloading(false);
    }
  }, [connectionParams]);

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
          sx={{ textTransform: 'capitalize' }}
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
        </ToggleButtonGroup>
        <Button
          size="small"
          color="inherit"
          startIcon={<ChatIcon sx={{ fontSize: '0.875rem' }} />}
          onClick={() => setWizardOpen(true)}
          sx={{ ml: 1, fontSize: '0.7rem', textTransform: 'none', whiteSpace: 'nowrap' }}
        >
          New LLM Conversation Mock
        </Button>
        <Box sx={{ flex: 1 }} />
        <Typography variant="caption" color="text.secondary" sx={{ display: { xs: 'none', md: 'block' } }}>
          ⌘K search · ⌘L clear · Esc filter
        </Typography>
        <Tooltip title={downloading ? 'Downloading HAR...' : 'Download HAR'}>
          <span>
            <IconButton size="small" color="inherit" onClick={() => void handleDownloadHar()} disabled={downloading}>
              <DownloadIcon fontSize="small" />
            </IconButton>
          </span>
        </Tooltip>
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
      </Toolbar>
      <ConversationWizard
        open={wizardOpen}
        onClose={() => setWizardOpen(false)}
        connectionParams={connectionParams}
      />
    </MuiAppBar>
  );
}
