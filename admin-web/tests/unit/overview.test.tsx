import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import OverviewPage from '../../src/pages/OverviewPage';
import { I18nProvider } from '../../src/i18n';
import { ApiException, type Overview } from '../../src/api';

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api')>();
  return { ...actual, api: vi.fn() };
});

import { api } from '../../src/api';

const apiMock = api as ReturnType<typeof vi.fn>;

const overview = (over: Partial<Overview> = {}): Overview => ({
  kpis: {
    registrationsToday: 12,
    dau: 345,
    wau: 2100,
    messagesToday: 9876,
    activeCalls: 3,
    databaseBytes: 2 * 1024 * 1024,
    uploadsBytes: 512 * 1024,
    pendingAlerts: 7,
    errorRate: 0.015,
    queue: {
      depth: 4,
      dropped: 2,
      scanned: 300,
      alertsCreated: 7,
      enabled: true,
      dryRun: false,
    },
  },
  days: 14,
  series: [
    { date: '2026-11-04', registrations: 3, messages: 400 },
    { date: '2026-11-05', registrations: 9, messages: 650 },
  ],
  generatedAt: '2026-11-17T08:00:00Z',
  ...over,
});

const renderPage = () =>
  render(
    <I18nProvider>
      <OverviewPage />
    </I18nProvider>,
  );

const tile = (label: string) =>
  screen.getByText(label).closest('.kpi') as HTMLElement;

beforeEach(() => {
  apiMock.mockReset();
  localStorage.clear();
});

describe('OverviewPage', () => {
  it('renders KPI tiles with formatted values and resolved labels', async () => {
    apiMock.mockResolvedValue(overview());
    const { container } = renderPage();

    expect(await screen.findByText('Signups today')).toBeInTheDocument();
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/overview');
    expect(tile('Signups today')).toHaveTextContent('12');
    expect(tile('Active today')).toHaveTextContent('345');
    expect(tile('Messages today')).toHaveTextContent('9876');
    expect(tile('Open alerts')).toHaveTextContent('7');
    // bytes and rates are formatted, not raw numbers
    expect(tile('Database')).toHaveTextContent('2.0 MB');
    expect(tile('Uploads')).toHaveTextContent('512.0 KB');
    expect(tile('5xx rate')).toHaveTextContent('1.50%');
    // queue depth uses the interpolated detail string
    expect(tile('Scan queue')).toHaveTextContent('4');
    expect(tile('Scan queue')).toHaveTextContent('2 dropped / 300 scanned');
    // i18n keys are resolved, not leaked raw
    expect(container.textContent).not.toMatch(/\b(kpi|chart|common)\.[a-z]/);
  });

  it('renders the two series charts', async () => {
    apiMock.mockResolvedValue(overview());
    renderPage();
    expect(await screen.findByRole('img', { name: 'Signups' })).toBeInTheDocument();
    expect(screen.getByRole('img', { name: 'Messages' })).toBeInTheDocument();
  });

  it('marks a disabled queue and dry-run mode', async () => {
    apiMock.mockResolvedValue(
      overview({
        kpis: {
          ...overview().kpis,
          queue: { depth: 0, dropped: 0, scanned: 0, alertsCreated: 0, enabled: false, dryRun: true },
        },
      }),
    );
    renderPage();
    expect(await screen.findByText('Scan queue')).toBeInTheDocument();
    expect(tile('Scan queue')).toHaveTextContent('off');
    expect(tile('Open alerts')).toHaveTextContent('dry-run');
  });

  it('shows an error block and retries', async () => {
    const user = userEvent.setup();
    apiMock.mockRejectedValueOnce(new ApiException(500, 'X', 'boom'));
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
    apiMock.mockResolvedValueOnce(overview());
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Signups today')).toBeInTheDocument();
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

describe('OverviewPage live refresh', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    delete (document as unknown as Record<string, unknown>).visibilityState;
  });

  it('polls the overview endpoint every 60 seconds while visible', async () => {
    apiMock.mockResolvedValue(overview());
    renderPage();
    await act(async () => {});
    expect(apiMock).toHaveBeenCalledTimes(1);
    await act(async () => {
      vi.advanceTimersByTime(60_000);
    });
    expect(apiMock).toHaveBeenCalledTimes(2);
    await act(async () => {
      vi.advanceTimersByTime(120_000);
    });
    expect(apiMock).toHaveBeenCalledTimes(4);
    expect(apiMock).toHaveBeenLastCalledWith('/api/v1/admin/overview');
  });

  it('pauses polling while the tab is hidden and refreshes on return', async () => {
    apiMock.mockResolvedValue(overview());
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

  it('keeps the last good data when a poll fails and recovers on retry', async () => {
    apiMock.mockResolvedValueOnce(overview());
    renderPage();
    await act(async () => {});
    expect(screen.getByText('Signups today')).toBeInTheDocument();
    apiMock.mockRejectedValueOnce(new ApiException(500, 'X', 'boom'));
    await act(async () => {
      vi.advanceTimersByTime(60_000);
    });
    expect(apiMock).toHaveBeenCalledTimes(2);
    // stale data stays on screen; the error block does not replace it
    expect(screen.getByText('Signups today')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByText(/Refresh failed/)).toBeInTheDocument();
    // the manual refresh button is the retry affordance
    apiMock.mockResolvedValueOnce(
      overview({ kpis: { ...overview().kpis, registrationsToday: 20 } }),
    );
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    });
    expect(apiMock).toHaveBeenCalledTimes(3);
    expect(tile('Signups today')).toHaveTextContent('20');
    expect(screen.queryByText(/Refresh failed/)).not.toBeInTheDocument();
  });

  it('refresh button triggers an immediate fetch', async () => {
    apiMock.mockResolvedValue(overview());
    renderPage();
    await act(async () => {});
    expect(apiMock).toHaveBeenCalledTimes(1);
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    });
    expect(apiMock).toHaveBeenCalledTimes(2);
    expect(apiMock).toHaveBeenLastCalledWith('/api/v1/admin/overview');
  });

  it('shows when the data was last updated', async () => {
    apiMock.mockResolvedValue(overview());
    renderPage();
    await act(async () => {});
    expect(screen.getByText(/^Updated /)).toBeInTheDocument();
  });
});

describe('OverviewPage trend chips', () => {
  it('renders delta chips vs yesterday on the signups and messages tiles', async () => {
    apiMock.mockResolvedValue(overview());
    renderPage();
    // series is [3, 9] so yesterday is 3: signups 12 -> +9, messages 9876 vs 400 -> +9476
    expect(await screen.findByText('Signups today')).toBeInTheDocument();
    expect(tile('Signups today').querySelector('.chip.success')).toHaveTextContent('+9');
    expect(tile('Messages today').querySelector('.chip.success')).toHaveTextContent('+9476');
  });

  it('renders a negative delta with the down tone', async () => {
    apiMock.mockResolvedValue(
      overview({ kpis: { ...overview().kpis, registrationsToday: 1 } }),
    );
    renderPage();
    expect(await screen.findByText('Signups today')).toBeInTheDocument();
    expect(tile('Signups today').querySelector('.chip.critical')).toHaveTextContent('-2');
  });

  it('renders no delta chip when the series is too short', async () => {
    apiMock.mockResolvedValue(
      overview({ series: [{ date: '2026-11-17', registrations: 12, messages: 9876 }] }),
    );
    renderPage();
    expect(await screen.findByText('Signups today')).toBeInTheDocument();
    expect(tile('Signups today').querySelector('.chip')).toBeNull();
    expect(tile('Messages today').querySelector('.chip')).toBeNull();
  });
});
