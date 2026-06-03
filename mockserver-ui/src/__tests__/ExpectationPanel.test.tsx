import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ExpectationPanel from '../components/ExpectationPanel';
import { useDashboardStore } from '../store';

describe('ExpectationPanel', () => {
  beforeEach(() => {
    useDashboardStore.setState({
      activeExpectations: [],
      expectationSearch: '',
    });
  });

  it('shows empty state when no expectations', () => {
    render(<ExpectationPanel />);
    expect(screen.getByText(/No active expectations/)).toBeInTheDocument();
  });

  it('renders expectations with descriptions', () => {
    useDashboardStore.setState({
      activeExpectations: [
        {
          key: 'exp1',
          description: 'GET /api/users',
          value: { httpRequest: { method: 'GET', path: '/api/users' } },
        },
      ],
    });

    render(<ExpectationPanel />);
    // After the layout refactor the method and path render in separate
    // elements so the path can be right-aligned while the method stays left.
    expect(screen.getByText('GET')).toBeInTheDocument();
    expect(screen.getByText('/api/users')).toBeInTheDocument();
  });

  it('filters expectations by search', async () => {
    const user = userEvent.setup();
    useDashboardStore.setState({
      activeExpectations: [
        { key: 'exp1', description: 'GET /users', value: { httpRequest: { method: 'GET', path: '/users' } } },
        { key: 'exp2', description: 'POST /orders', value: { httpRequest: { method: 'POST', path: '/orders' } } },
      ],
    });

    render(<ExpectationPanel />);
    const searchInput = screen.getByPlaceholderText('Search...');
    await user.type(searchInput, 'orders');

    expect(screen.queryByText('/users')).not.toBeInTheDocument();
    expect(screen.getByText('/orders')).toBeInTheDocument();
  });

  it('shows count badge with correct number', () => {
    useDashboardStore.setState({
      activeExpectations: [
        { key: 'exp1', value: {} },
        { key: 'exp2', value: {} },
        { key: 'exp3', value: {} },
      ],
    });

    render(<ExpectationPanel />);
    const chip = document.querySelector('.MuiChip-label');
    expect(chip).toHaveTextContent('3');
  });
});
