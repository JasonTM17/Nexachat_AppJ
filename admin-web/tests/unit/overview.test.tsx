import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
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
