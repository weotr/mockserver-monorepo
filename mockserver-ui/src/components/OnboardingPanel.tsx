import Box from '@mui/material/Box';
import Card from '@mui/material/Card';
import CardContent from '@mui/material/CardContent';
import CardActions from '@mui/material/CardActions';
import Typography from '@mui/material/Typography';
import Button from '@mui/material/Button';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import SwapHorizIcon from '@mui/icons-material/SwapHoriz';
import PlayCircleIcon from '@mui/icons-material/PlayCircle';
import MenuBookIcon from '@mui/icons-material/MenuBook';
import { useState } from 'react';
import OpenApiImportDialog from './OpenApiImportDialog';
import type { ConnectionParams } from '../hooks/useConnectionParams';

interface OnboardingPanelProps {
  connectionParams: ConnectionParams;
}

interface ActionCardProps {
  icon: React.ReactNode;
  title: string;
  description: string;
  action: React.ReactNode;
}

function ActionCard({ icon, title, description, action }: ActionCardProps) {
  return (
    <Card
      variant="outlined"
      sx={{
        flex: '1 1 260px',
        maxWidth: 340,
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <CardContent sx={{ flex: 1 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
          {icon}
          <Typography variant="subtitle1" sx={{ fontWeight: 600 }}>
            {title}
          </Typography>
        </Box>
        <Typography variant="body2" color="text.secondary">
          {description}
        </Typography>
      </CardContent>
      <CardActions sx={{ px: 2, pb: 2 }}>{action}</CardActions>
    </Card>
  );
}

export default function OnboardingPanel({ connectionParams }: OnboardingPanelProps) {
  const [openApiOpen, setOpenApiOpen] = useState(false);

  return (
    <Box
      sx={{
        flex: 1,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        p: 4,
        overflow: 'auto',
      }}
    >
      <Typography variant="h5" gutterBottom sx={{ fontWeight: 700 }}>
        Welcome to MockServer
      </Typography>
      <Typography
        variant="body1"
        color="text.secondary"
        sx={{ mb: 4, maxWidth: 600, textAlign: 'center' }}
      >
        No expectations or traffic recorded yet. Pick an action below to get
        started, or switch to any tab when you are ready.
      </Typography>

      <Box
        sx={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: 2,
          justifyContent: 'center',
          maxWidth: 1100,
        }}
      >
        <ActionCard
          icon={<UploadFileIcon color="primary" />}
          title="Import an OpenAPI Spec"
          description="Upload an OpenAPI / Swagger definition and MockServer will generate stubs for every endpoint automatically."
          action={
            <Button
              size="small"
              variant="contained"
              startIcon={<UploadFileIcon />}
              onClick={() => setOpenApiOpen(true)}
            >
              Import OpenAPI
            </Button>
          }
        />

        <ActionCard
          icon={<SwapHorizIcon color="primary" />}
          title="Record Live Traffic"
          description="Run MockServer as a proxy to capture real API traffic. Start with the --proxyRemoteHost and --proxyRemotePort flags, or use the docker-compose proxy recipe."
          action={
            <Button
              size="small"
              variant="outlined"
              href="https://www.mock-server.com/mock_server/self_hosting_mockserver.html"
              target="_blank"
              rel="noopener"
            >
              Proxy setup guide
            </Button>
          }
        />

        <ActionCard
          icon={<PlayCircleIcon color="primary" />}
          title="Try a Quick-Start Recipe"
          description="Spin up a ready-made docker-compose example: mock from OpenAPI, record-replay proxy, validation proxy, or chaos proxy."
          action={
            <Button
              size="small"
              variant="outlined"
              href="https://github.com/mock-server/mockserver-monorepo/tree/master/examples/docker-compose"
              target="_blank"
              rel="noopener"
            >
              View recipes
            </Button>
          }
        />

        <ActionCard
          icon={<MenuBookIcon color="primary" />}
          title="Explore the Dashboard"
          description="Learn about all the features available in the dashboard: live traffic inspection, mock composition, chaos testing, drift detection, and more."
          action={
            <Button
              size="small"
              variant="outlined"
              href="https://www.mock-server.com/mock_server/mockserver_ui.html"
              target="_blank"
              rel="noopener"
            >
              Dashboard docs
            </Button>
          }
        />
      </Box>

      <OpenApiImportDialog
        open={openApiOpen}
        onClose={() => setOpenApiOpen(false)}
        connectionParams={connectionParams}
      />
    </Box>
  );
}
