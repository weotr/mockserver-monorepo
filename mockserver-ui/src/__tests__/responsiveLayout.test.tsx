/**
 * Responsive-layout tests (Unit W4).
 *
 * The dashboard was desktop-only — no `useMediaQuery`/breakpoint usage existed.
 * These tests pin the new responsive behaviour at both ends of the breakpoint:
 *
 *  - desktop default (jsdom has no matchMedia → useMediaQuery returns false):
 *    a dialog is NOT fullScreen.
 *  - simulated small viewport (matchMedia reports every query as matching):
 *    the same dialog IS fullScreen (MuiDialog-paperFullScreen on the paper).
 *
 * We drive the breakpoint by stubbing `window.matchMedia`, which is what
 * MUI's `useMediaQuery` reads. OpenApiImportDialog is used as a representative
 * dialog because it renders synchronously with no effects/fetches.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { ThemeProvider } from '@mui/material/styles';
import { buildTheme } from '../theme';
import OpenApiImportDialog from '../components/OpenApiImportDialog';

const theme = buildTheme('dark');
const params = { host: '127.0.0.1', port: '1080', secure: false };

/**
 * Install a matchMedia stub. When `matches` is true every media query reports a
 * match, so `useMediaQuery(theme.breakpoints.down('sm'))` resolves to true and
 * the dialog renders fullScreen.
 */
function stubMatchMedia(matches: boolean) {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches,
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })) as unknown as typeof window.matchMedia;
}

function renderDialog() {
  return render(
    <ThemeProvider theme={theme}>
      <OpenApiImportDialog open onClose={() => {}} connectionParams={params} />
    </ThemeProvider>,
  );
}

afterEach(() => {
  cleanup();
  // Reset matchMedia to the jsdom default (absent) so other tests see desktop.
  // @ts-expect-error allow deleting the optional stub
  delete window.matchMedia;
});

describe('responsive dialog fullScreen', () => {
  // MUI renders the dialog paper as the element carrying role="dialog"; the
  // fullScreen variant adds the `MuiDialog-paperFullScreen` class to it.
  it('is not fullScreen on a desktop viewport (default)', () => {
    // jsdom has no matchMedia → useMediaQuery returns false → desktop layout.
    renderDialog();
    const paper = screen.getByRole('dialog');
    expect(paper.className).not.toMatch(/MuiDialog-paperFullScreen/);
  });

  it('is fullScreen on a small (phone) viewport', () => {
    stubMatchMedia(true);
    renderDialog();
    const paper = screen.getByRole('dialog');
    expect(paper.className).toMatch(/MuiDialog-paperFullScreen/);
  });
});
