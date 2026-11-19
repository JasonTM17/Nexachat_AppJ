import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SystemPage from '../../src/pages/SystemPage';
import { I18nProvider } from '../../src/i18n';
import { ApiException, type SystemHealth } from '../../src/api';

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api')>();
  return { ...actual, api: vi.fn() };
});

import { api } from '../../src/api';

const apiMock = api as ReturnType<typeof vi.fn>;

const health = (over: Partial<SystemHealth> = {}): SystemHealth => ({
  status: 'UP',
  checkedAt: '2026-11-17T08:00:00Z',
  uptimeSeconds: 190_000, // 2d 4h
  services: [
    { name: 'postgres', status: 'UP', detail: '12ms' },
    { name: 'redis', status: 'DEGRADED', detail: 'slow pings' },
    { name: 'scanner', status: 'FLAPPY', detail: '' },
  ],
  ...over,
});

const renderPage = () =>
  render(
    <I18nProvider>
      <SystemPage />
    </I18nProvider>,
  );

beforeEach(() => {
  apiMock.mockReset();
  localStorage.clear();
});

describe('SystemPage', () => {
  it('renders the status card, uptime and service list', async () => {
    apiMock.mockResolvedValue(health());
    const { container } = renderPage();

    expect(await screen.findByText('Services')).toBeInTheDocument();
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/system/health');
    expect(screen.getByText('Status')).toBeInTheDocument();
    expect(screen.getByText('Uptime')).toBeInTheDocument();
    // 190_000s formats as days+hours
    expect(screen.getByText('2d 4h')).toBeInTheDocument();
    // service names and per-service statuses resolve through i18n
    expect(screen.getByText('postgres')).toBeInTheDocument();
    expect(screen.getByText('redis')).toBeInTheDocument();
    expect(screen.getByText('scanner')).toBeInTheDocument();
    expect(screen.getAllByText('UP').length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText('Degraded')).toBeInTheDocument();
    // unknown service status must not render as healthy
    expect(screen.getByText('Unknown')).toBeInTheDocument();
    expect(screen.getByText('slow pings')).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/\b(system|common)\.[a-z]/);
  });

  it('renders a DOWN overall status with the localized label', async () => {
    apiMock.mockResolvedValue(health({ status: 'OUTAGE' }));
    renderPage();
    expect(await screen.findByText('DOWN')).toBeInTheDocument();
  });

  it('shows an error block and retries', async () => {
    const user = userEvent.setup();
    apiMock.mockRejectedValueOnce(new ApiException(500, 'X', 'boom'));
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
    apiMock.mockResolvedValueOnce(health());
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Services')).toBeInTheDocument();
    expect(apiMock).toHaveBeenCalledTimes(2);
  });
});

// jsdom keeps visibilityState on the prototype; shadow it per test so the
// polling tests can flip the tab between visible and hidden.
const setVisibility = (state: 'visible' | 'hidden') => {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => state,
  });
};

describe('SystemPage live refresh', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    delete (document as unknown as Record<string, unknown>).visibilityState;
  });

  it('polls every 30 seconds while visible and backs off after failures', async () => {
    apiMock.mockResolvedValue(health());
    renderPage();
    await act(async () => {});
    expect(apiMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      vi.advanceTimersByTime(30_000);
    });
    expect(apiMock).toHaveBeenCalledTimes(2);

    apiMock.mockRejectedValue(new ApiException(500, 'X', 'boom'));
    await act(async () => {
      vi.advanceTimersByTime(30_000);
    });
    expect(apiMock).toHaveBeenCalledTimes(3);
    // one consecutive failure → the next poll waits 60s
    await act(async () => {
      vi.advanceTimersByTime(30_000);
    });
    expect(apiMock).toHaveBeenCalledTimes(3);
    await act(async () => {
      vi.advanceTimersByTime(30_000);
    });
    expect(apiMock).toHaveBeenCalledTimes(4);
    // two consecutive failures → the next poll waits 120s
    await act(async () => {
      vi.advanceTimersByTime(60_000);
    });
    expect(apiMock).toHaveBeenCalledTimes(4);
    await act(async () => {
      vi.advanceTimersByTime(60_000);
    });
    expect(apiMock).toHaveBeenCalledTimes(5);
  });

  it('pauses polling while the tab is hidden and refreshes on return', async () => {
    apiMock.mockResolvedValue(health());
    renderPage();
    await act(async () => {});
    expect(apiMock).toHaveBeenCalledTimes(1);

    setVisibility('hidden');
    await act(async () => {
      vi.advanceTimersByTime(300_000);
    });
    expect(apiMock).toHaveBeenCalledTimes(1);

    setVisibility('visible');
    await act(async () => {
      fireEvent(document, new Event('visibilitychange'));
    });
    expect(apiMock).toHaveBeenCalledTimes(2);
  });
});
