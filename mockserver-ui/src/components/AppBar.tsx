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
import RuleIcon from '@mui/icons-material/Rule';
import ClockDialog from './ClockDialog';
import ConfigurationDialog from './ConfigurationDialog';
import ExplainUnmatchedDialog from './ExplainUnmatchedDialog';
import MatcherPlaygroundDialog from './MatcherPlaygroundDialog';
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
import SavingsIcon from '@mui/icons-material/Savings';
import BoltIcon from '@mui/icons-material/Bolt';
import TrendingUpIcon from '@mui/icons-material/TrendingUp';
import CompareArrowsIcon from '@mui/icons-material/CompareArrows';
import PlaylistAddCheckIcon from '@mui/icons-material/PlaylistAddCheck';
import FactCheckIcon from '@mui/icons-material/FactCheck';
import HubOutlinedIcon from '@mui/icons-material/HubOutlined';
import PanToolIcon from '@mui/icons-material/PanTool';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import RpcIcon from '@mui/icons-material/Cable';
import Button from '@mui/material/Button';
import Select from '@mui/material/Select';
import type { SelectChangeEvent } from '@mui/material/Select';
import useMediaQuery from '@mui/material/useMediaQuery';
import { useTheme } from '@mui/material/styles';
import MenuIcon from '@mui/icons-material/Menu';
import KeyboardIcon from '@mui/icons-material/Keyboard';
import type { ReactNode } from 'react';
// Snackbar/Alert removed — mode errors now use the app-wide notification store
import BuildIcon from '@mui/icons-material/Build';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import DownloadIcon from '@mui/icons-material/Download';
import { useState, useEffect, useLayoutEffect, useRef, useCallback } from 'react';
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
import { humanizeError } from '../lib/errorMessage';
import WsdlImportDialog from './WsdlImportDialog';
import OpenApiImportDialog from './OpenApiImportDialog';
import PactExportDialog from './PactExportDialog';
import OidcDialog from './OidcDialog';
import SamlDialog from './SamlDialog';
import ShortcutsDialog from './ShortcutsDialog';
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
import BaselineCompareDialog from './BaselineCompareDialog';

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

interface NavTab {
  value: ViewMode;
  label: string;
  ariaLabel: string;
  /**
   * One-line summary of what the tab is for, shown in a bar under the nav.
   * Optional — tabs that omit it (e.g. Get Started, which is self-explanatory)
   * render no description bar.
   */
  description?: string;
  icon: ReactNode;
}

// Single source of truth for the navigation tabs, in priority order. On wide
// screens a measured "priority+ / progressive overflow" strip renders as many
// tabs inline as fit (longest fitting prefix of this list) and moves the
// remainder into a labelled "More" overflow Menu; when every tab fits there is
// no "More" button at all. On narrow screens the full list lives in the
// hamburger Menu. The order here is the order shown in every menu.
const NAV_TABS: NavTab[] = [
  { value: 'get-started', label: 'Get Started', ariaLabel: 'Get started view', icon: <RocketLaunchIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'dashboard', label: 'Dashboard', ariaLabel: 'Dashboard view', description: 'Live view of incoming requests, active expectations, and what matched.', icon: <DashboardIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'traffic', label: 'Traffic', ariaLabel: 'Traffic inspector view', description: 'Browse recorded request and response traffic — select an item to open its full details.', icon: <TrafficIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'sessions', label: 'Trace', ariaLabel: 'Trace inspector view', description: 'Trace related requests grouped together — including LLM agent runs — to debug multi-step flows end to end.', icon: <AccountTreeIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'breakpoints', label: 'Breakpoints', ariaLabel: 'Breakpoints view', description: 'Pause matching requests or responses mid-flight to inspect and edit them.', icon: <PanToolIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'composer', label: 'Mocks', ariaLabel: 'Mocks view', description: 'Create, edit, and manage mock expectations — quick mode for common cases, advanced mode for full control.', icon: <PostAddIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'chaos', label: 'Chaos', ariaLabel: 'Service chaos view', description: 'Inject latency, errors, and faults to test how your system handles failure.', icon: <BoltIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'performance', label: 'Performance', ariaLabel: 'Performance testing view', description: 'Create, run, and monitor load scenarios — drive traffic at a target and watch live throughput and latency.', icon: <TrendingUpIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'optimise', label: 'LLM Optimise', ariaLabel: 'LLM Optimise view', description: 'Analyse captured LLM traffic to optimise prompts, inference cost, safety, and speed.', icon: <SavingsIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'async', label: 'Async', ariaLabel: 'AsyncAPI broker mock view', description: 'Mock event-driven APIs from an AsyncAPI spec — publish test messages to Kafka, MQTT, and AMQP (RabbitMQ) brokers.', icon: <HubIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'grpc', label: 'gRPC', ariaLabel: 'gRPC services view', description: 'Mock gRPC services and inspect gRPC calls.', icon: <RpcIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'library', label: 'Library', ariaLabel: 'Library of captured content', description: 'Browse and reuse captured requests, responses, and content.', icon: <Inventory2Icon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'drift', label: 'Drift', ariaLabel: 'Drift detection view', description: 'Detect when your mocks drift away from the real API they stand in for.', icon: <CompareArrowsIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'verification', label: 'Verify', ariaLabel: 'Verification view', description: 'Assert which requests were — or were not — received.', icon: <PlaylistAddCheckIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'contract', label: 'Contract', ariaLabel: 'Contract test view', description: 'Validate mocks and traffic against an OpenAPI contract.', icon: <FactCheckIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'cluster', label: 'Cluster', ariaLabel: 'Cluster status view', description: 'Monitor MockServer cluster nodes and shared state.', icon: <HubOutlinedIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
  { value: 'metrics', label: 'Metrics', ariaLabel: 'Metrics view', description: 'Prometheus metrics plus memory and performance monitoring.', icon: <SpeedIcon sx={{ fontSize: '0.875rem', mr: 0.5 }} /> },
];

// Lookup of the active view's one-line description, for the bar under the nav.
// Exported so App can render it without duplicating the per-tab copy.
export const NAV_TAB_DESCRIPTIONS: Partial<Record<ViewMode, string>> = Object.fromEntries(
  NAV_TABS
    .filter((t): t is NavTab & { description: string } => Boolean(t.description))
    .map((t) => [t.value, t.description]),
);

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
  const theme = useTheme();
  // Below this width the inline tab strip would overflow into a hidden scroll
  // region, so collapse the nav into a "hamburger" Menu instead.
  const compactNav = useMediaQuery(theme.breakpoints.down('lg'));
  const [navAnchorEl, setNavAnchorEl] = useState<null | HTMLElement>(null);
  // Wide-screen "More" overflow menu holding the tabs that don't fit inline.
  const [moreNavAnchorEl, setMoreNavAnchorEl] = useState<null | HTMLElement>(null);

  // --- Priority-navigation measurement ---
  // We measure the REAL rendered buttons rather than a hidden mirror so there is
  // no duplicated, query-pollutable text in the DOM. `navRegionRef` is the box
  // whose width we fit into; `tabGroupRef` wraps the inline ToggleButtonGroup;
  // `moreButtonRef` is the live "More" button when present.
  const navRegionRef = useRef<HTMLDivElement | null>(null);
  const tabGroupRef = useRef<HTMLDivElement | null>(null);
  const moreButtonRef = useRef<HTMLButtonElement | null>(null);
  // Cached natural width per tab value, learned as each tab is observed inline.
  // Because we DEFAULT to all-inline, the very first layout measures every tab;
  // widths persist after a tab moves into the overflow menu (its button
  // unmounts but its cached width stays valid). Also caches the More-button
  // width to reserve when overflow occurs.
  const tabWidthsRef = useRef<Map<ViewMode, number>>(new Map());
  const moreButtonWidthRef = useRef<number>(0);
  // How many leading tabs render inline. Default to ALL so nothing disappears
  // before/without a real measurement (initial render, jsdom). Refined once real
  // widths are known and the available width is smaller than the full strip.
  const [inlineCount, setInlineCount] = useState<number>(NAV_TABS.length);

  // Recompute the largest prefix of NAV_TABS that fits the available width,
  // reserving room for the "More" button whenever at least one tab overflows.
  const recomputeInlineCount = useCallback(() => {
    const region = navRegionRef.current;
    if (!region) return;
    const available = region.clientWidth;
    const widths = tabWidthsRef.current;
    // Until we have a real width for every tab and a real container width, keep
    // everything inline (initial render, jsdom where getBoundingClientRect → 0).
    const allKnown = NAV_TABS.every((t) => (widths.get(t.value) ?? 0) > 0);
    if (!available || !allKnown) {
      setInlineCount(NAV_TABS.length);
      return;
    }
    const totalAll = NAV_TABS.reduce((sum, t) => sum + (widths.get(t.value) ?? 0), 0);
    // Whole strip fits → no "More" button at all.
    if (totalAll <= available) {
      setInlineCount(NAV_TABS.length);
      return;
    }
    // Otherwise reserve room for the "More" button and take the longest prefix
    // that still fits alongside it. At least one tab always overflows here.
    const reserve = moreButtonWidthRef.current;
    let used = 0;
    let count = 0;
    for (const tab of NAV_TABS) {
      const next = used + (widths.get(tab.value) ?? 0);
      if (next + reserve <= available) {
        used = next;
        count += 1;
      } else {
        break;
      }
    }
    setInlineCount(count);
  }, []);

  // After each layout, cache the natural width of every currently-inline button
  // (keyed by tab value) and the More-button width, then recompute the split.
  useLayoutEffect(() => {
    if (compactNav) return; // hamburger tier doesn't use the measured strip
    const group = tabGroupRef.current;
    if (group) {
      const tabEls = group.querySelectorAll<HTMLElement>('[data-nav-tab]');
      tabEls.forEach((el) => {
        const value = el.getAttribute('data-nav-tab') as ViewMode | null;
        const w = el.getBoundingClientRect().width;
        if (value && w > 0) tabWidthsRef.current.set(value, w);
      });
    }
    if (moreButtonRef.current) {
      const w = moreButtonRef.current.getBoundingClientRect().width;
      if (w > 0) moreButtonWidthRef.current = w;
    }
    recomputeInlineCount();
  }, [compactNav, inlineCount, recomputeInlineCount]);

  // Recompute on available-width changes via ResizeObserver, falling back to a
  // window 'resize' listener where ResizeObserver is unavailable.
  useEffect(() => {
    if (compactNav) return;
    const region = navRegionRef.current;
    if (!region) return;
    if (typeof ResizeObserver !== 'undefined') {
      const ro = new ResizeObserver(() => recomputeInlineCount());
      ro.observe(region);
      return () => ro.disconnect();
    }
    const onResize = () => recomputeInlineCount();
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [compactNav, recomputeInlineCount]);

  const inlineTabs = NAV_TABS.slice(0, inlineCount);
  const overflowTabs = NAV_TABS.slice(inlineCount);
  const hasOverflow = overflowTabs.length > 0;
  const activeOverflowTab = overflowTabs.find((t) => t.value === view);

  const connectionParams = useConnectionParams();
  const [mode, setModeState] = useState<MockServerMode | null>(null);
  const [modeMenuOpen, setModeMenuOpen] = useState(false);
  const [modeTooltipOpen, setModeTooltipOpen] = useState(false);
  const [toolsAnchorEl, setToolsAnchorEl] = useState<null | HTMLElement>(null);
  const [clockOpen, setClockOpen] = useState(false);
  const [configOpen, setConfigOpen] = useState(false);
  const [explainOpen, setExplainOpen] = useState(false);
  const [playgroundOpen, setPlaygroundOpen] = useState(false);
  const [oidcOpen, setOidcOpen] = useState(false);
  const [samlOpen, setSamlOpen] = useState(false);
  const [shortcutsOpen, setShortcutsOpen] = useState(false);
  const [asyncApiOpen, setAsyncApiOpen] = useState(false);
  const [wsdlOpen, setWsdlOpen] = useState(false);
  const [openApiOpen, setOpenApiOpen] = useState(false);
  const [pactOpen, setPactOpen] = useState(false);
  const [crudOpen, setCrudOpen] = useState(false);
  const [fileStoreOpen, setFileStoreOpen] = useState(false);
  const [diffOpen, setDiffOpen] = useState(false);
  const [baselineOpen, setBaselineOpen] = useState(false);
  // Mode errors are now surfaced through the app-wide notification store.
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
        setNotification({ message: humanizeError(e).message, severity: 'error' });
      });
  };

  // Styling for the inline ToggleButtonGroup nav strip.
  const toggleGroupSx = {
    ml: 1,
    flexShrink: 0,
    '& .MuiToggleButton-root': {
      py: 0.25,
      px: 1,
      fontSize: '0.7rem',
      textTransform: 'none',
      lineHeight: 1.4,
      whiteSpace: 'nowrap',
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
  } as const;

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
        {compactNav ? (
          <>
            <Tooltip title="Navigate">
              <IconButton
                size="small"
                color="inherit"
                aria-label="Open navigation menu"
                onClick={(e) => setNavAnchorEl(e.currentTarget)}
                sx={{ ml: 1 }}
              >
                <MenuIcon fontSize="small" />
              </IconButton>
            </Tooltip>
            <Typography variant="subtitle2" sx={{ fontWeight: 600, fontSize: '0.8rem', whiteSpace: 'nowrap' }}>
              {NAV_TABS.find((t) => t.value === view)?.label ?? ''}
            </Typography>
            <Menu
              anchorEl={navAnchorEl}
              open={Boolean(navAnchorEl)}
              onClose={() => setNavAnchorEl(null)}
            >
              {NAV_TABS.map((tab) => (
                <MenuItem
                  key={tab.value}
                  selected={view === tab.value}
                  aria-label={tab.ariaLabel}
                  onClick={() => {
                    setView(tab.value);
                    setNavAnchorEl(null);
                  }}
                >
                  <ListItemIcon>{tab.icon}</ListItemIcon>
                  <ListItemText>{tab.label}</ListItemText>
                </MenuItem>
              ))}
            </Menu>
          </>
        ) : (
          <Box
            ref={navRegionRef}
            sx={{ flex: 1, minWidth: 0, display: 'flex', alignItems: 'center', position: 'relative', overflow: 'hidden' }}
          >
          <ToggleButtonGroup
            ref={tabGroupRef}
            value={view}
            exclusive
            size="small"
            onChange={(_, newView: ViewMode | null) => {
              if (newView !== null) setView(newView);
            }}
            sx={toggleGroupSx}
          >
            {inlineTabs.map((tab) => (
              <ToggleButton key={tab.value} value={tab.value} aria-label={tab.ariaLabel} data-nav-tab={tab.value}>
                {tab.icon}
                {tab.label}
              </ToggleButton>
            ))}
          </ToggleButtonGroup>
          {/* "More" overflow menu holding the tabs that don't fit inline. It
              only renders when at least one tab overflows. When the active view
              lives in here, the button shows that view's label so the current
              selection is always visible. */}
          {hasOverflow && (
            <>
              <Button
                ref={moreButtonRef}
                size="small"
                color="inherit"
                aria-label="More views"
                aria-haspopup="menu"
                endIcon={<ExpandMoreIcon sx={{ fontSize: '0.875rem' }} />}
                onClick={(e) => setMoreNavAnchorEl(e.currentTarget)}
                sx={{
                  ml: 0.5,
                  py: 0.25,
                  px: 1,
                  fontSize: '0.7rem',
                  textTransform: 'none',
                  lineHeight: 1.4,
                  whiteSpace: 'nowrap',
                  flexShrink: 0,
                  ...(activeOverflowTab ? { backgroundColor: 'rgba(255, 255, 255, 0.18)' } : {}),
                }}
              >
                {activeOverflowTab?.label ?? 'More'}
              </Button>
              <Menu
                anchorEl={moreNavAnchorEl}
                open={Boolean(moreNavAnchorEl)}
                onClose={() => setMoreNavAnchorEl(null)}
              >
                {overflowTabs.map((tab) => (
                  <MenuItem
                    key={tab.value}
                    selected={view === tab.value}
                    aria-label={tab.ariaLabel}
                    onClick={() => {
                      setView(tab.value);
                      setMoreNavAnchorEl(null);
                    }}
                  >
                    <ListItemIcon>{tab.icon}</ListItemIcon>
                    <ListItemText>{tab.label}</ListItemText>
                  </MenuItem>
                ))}
              </Menu>
            </>
          )}
          </Box>
        )}
        <Box sx={{ flex: compactNav ? 1 : '0 0 auto' }} />
        <Tooltip title="Keyboard shortcuts">
          <IconButton size="small" color="inherit" onClick={() => setShortcutsOpen(true)} aria-label="Keyboard shortcuts">
            <KeyboardIcon fontSize="small" />
          </IconButton>
        </Tooltip>
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
        <Tooltip title="Matcher test playground — try a request against a candidate expectation">
          <IconButton size="small" color="inherit" onClick={() => setPlaygroundOpen(true)} aria-label="Matcher test playground">
            <RuleIcon fontSize="small" />
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
          <Tooltip
            title={MODE_DESCRIPTIONS[mode]}
            open={modeTooltipOpen && !modeMenuOpen}
            onOpen={() => setModeTooltipOpen(true)}
            onClose={() => setModeTooltipOpen(false)}
          >
            <Select
              value={mode}
              onChange={handleModeChange}
              open={modeMenuOpen}
              onOpen={() => setModeMenuOpen(true)}
              onClose={() => setModeMenuOpen(false)}
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
            aria-label="Clear logs, expectations, or reset server"
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
              setSamlOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><VpnKeyIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Mock SAML provider…</ListItemText>
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
          <MenuItem
            onClick={() => {
              setBaselineOpen(true);
              setToolsAnchorEl(null);
            }}
          >
            <ListItemIcon><CompareArrowsIcon fontSize="small" /></ListItemIcon>
            <ListItemText>Compare against baseline…</ListItemText>
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
        {/* Mode errors are surfaced through the app-wide notification store */}
      </Toolbar>
      <ClockDialog open={clockOpen} onClose={() => setClockOpen(false)} connectionParams={connectionParams} />
      <ConfigurationDialog open={configOpen} onClose={() => setConfigOpen(false)} connectionParams={connectionParams} />
      <ExplainUnmatchedDialog open={explainOpen} onClose={() => setExplainOpen(false)} connectionParams={connectionParams} />
      <MatcherPlaygroundDialog open={playgroundOpen} onClose={() => setPlaygroundOpen(false)} />
      <OidcDialog open={oidcOpen} onClose={() => setOidcOpen(false)} connectionParams={connectionParams} />
      <SamlDialog open={samlOpen} onClose={() => setSamlOpen(false)} connectionParams={connectionParams} />
      <ShortcutsDialog open={shortcutsOpen} onClose={() => setShortcutsOpen(false)} />
      <AsyncApiDialog open={asyncApiOpen} onClose={() => setAsyncApiOpen(false)} connectionParams={connectionParams} />
      <CrudDialog open={crudOpen} onClose={() => setCrudOpen(false)} connectionParams={connectionParams} />
      <FileStoreDialog open={fileStoreOpen} onClose={() => setFileStoreOpen(false)} connectionParams={connectionParams} />
      <DiffRequestsDialog open={diffOpen} onClose={() => setDiffOpen(false)} connectionParams={connectionParams} />
      <BaselineCompareDialog open={baselineOpen} onClose={() => setBaselineOpen(false)} connectionParams={connectionParams} />
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
