import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Panel from '../components/Panel';

describe('Panel', () => {
  it('renders title and count badge', () => {
    render(
      <Panel title="Test Panel" count={42} searchValue="" onSearchChange={() => {}}>
        <div>content</div>
      </Panel>,
    );
    expect(screen.getByText('Test Panel')).toBeInTheDocument();
    expect(screen.getByText('42')).toBeInTheDocument();
  });

  it('renders children', () => {
    render(
      <Panel title="Test" count={0} searchValue="" onSearchChange={() => {}}>
        <div>child content</div>
      </Panel>,
    );
    expect(screen.getByText('child content')).toBeInTheDocument();
  });

  it('renders search input with current value', () => {
    render(
      <Panel title="Test" count={0} searchValue="hello" onSearchChange={() => {}}>
        <div />
      </Panel>,
    );
    const input = screen.getByPlaceholderText('Search...');
    expect(input).toHaveValue('hello');
  });

  it('applies a transition and a hover style to the panel surface for motion', () => {
    const { container } = render(
      <Panel title="Motion" count={1} searchValue="" onSearchChange={() => {}}>
        <div>content</div>
      </Panel>,
    );
    const paper = container.querySelector('.MuiPaper-root') as HTMLElement;
    expect(paper).not.toBeNull();
    // MUI emits the sx transition + :hover rules into emotion <style> tags in the
    // head; assert the panel's generated CSS carries the hover motion.
    const css = Array.from(document.querySelectorAll('style'))
      .map((s) => s.textContent ?? '')
      .join('\n');
    expect(css).toMatch(/transition:[^;]*box-shadow/);
    expect(css).toMatch(/transition:[^;]*border-color/);
    expect(css).toContain(':hover');
  });

  it('marks the content region as a polite live region when liveRegion is set', () => {
    render(
      <Panel title="Logs" count={1} searchValue="" onSearchChange={() => {}} liveRegion>
        <div>log row</div>
      </Panel>,
    );
    const region = screen.getByRole('log');
    expect(region).toHaveAttribute('aria-live', 'polite');
    expect(region).toContainHTML('log row');
  });

  it('does not announce a live region by default', () => {
    render(
      <Panel title="Quiet" count={1} searchValue="" onSearchChange={() => {}}>
        <div>row</div>
      </Panel>,
    );
    expect(screen.queryByRole('log')).not.toBeInTheDocument();
  });

  it('calls onSearchChange when typing in search', async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();

    render(
      <Panel title="Test" count={0} searchValue="" onSearchChange={onChange}>
        <div />
      </Panel>,
    );

    const input = screen.getByPlaceholderText('Search...');
    await user.type(input, 'abc');

    expect(onChange).toHaveBeenCalledTimes(3);
    expect(onChange).toHaveBeenLastCalledWith('c');
  });
});
