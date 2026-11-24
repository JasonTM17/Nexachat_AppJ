import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AuditPage from '../../src/pages/AuditPage';
import { I18nProvider } from '../../src/i18n';
import { type AuditEntry, type Page } from '../../src/api';

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api')>();
  return { ...actual, api: vi.fn() };
});

import { api } from '../../src/api';

const apiMock = api as ReturnType<typeof vi.fn>;

const entry = (over: Partial<AuditEntry> = {}): AuditEntry => ({
  id: 1,
  actorId: 'u-admin',
  actorRole: 'ADMIN',
  action: 'USER_SUSPEND',
  targetType: 'USER',
  targetId: 'u-1',
  reason: 'spam farm',
  detail: { before: null, after: 'suspended' },
  ip: '203.0.113.7',
  createdAt: '2026-11-17T08:00:00Z',
  ...over,
});

const page = (items: AuditEntry[]): Page<AuditEntry> => ({
  items,
  page: 0,
  size: 25,
  total: items.length,
  hasMore: false,
});

const renderPage = () =>
  render(
    <I18nProvider>
      <AuditPage />
    </I18nProvider>,
  );

beforeEach(() => {
  apiMock.mockReset();
  localStorage.clear();
});

describe('AuditPage', () => {
  it('lists entries with actor, action chip and resolved labels', async () => {
    apiMock.mockResolvedValue(page([entry()]));
    const { container } = renderPage();

    const row = (await screen.findByText('u-admin')).closest('tr') as HTMLElement;
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/audit?page=0&size=25');
    expect(within(row).getByText('ADMIN')).toBeInTheDocument();
    expect(within(row).getByText('USER_SUSPEND')).toBeInTheDocument();
    expect(within(row).getByText('u-1')).toBeInTheDocument();
    expect(within(row).getByText('spam farm')).toBeInTheDocument();
    // i18n keys are resolved, not leaked raw
    expect(screen.getByRole('columnheader', { name: 'Action' })).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/\b(audit|common)\.[a-z]/);
  });

  it('marks denied actions distinctly', async () => {
    apiMock.mockResolvedValue(page([entry({ action: 'EXPORT_DENIED' })]));
    renderPage();
    const chip = await screen.findByText('EXPORT_DENIED');
    expect(chip.className).toContain('high');
  });

  it('shows the empty state', async () => {
    apiMock.mockResolvedValue(page([]));
    renderPage();
    expect(await screen.findByText('Nothing here')).toBeInTheDocument();
    // no filters are set: generic hint, nothing to clear
    expect(screen.getByText('No results to show right now.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Clear filters' })).toBeNull();
  });

  it('stays in the loading state while the request is pending', () => {
    // A pending promise keeps `data` null — the page renders the spinner.
    apiMock.mockImplementation(() => new Promise(() => {}));
    renderPage();
    expect(screen.getByRole('status')).toHaveTextContent('Loading…');
  });

  it('does not send an incomplete actor id to the server', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValue(page([entry()]));
    renderPage();
    await screen.findByText('u-admin');
    const initialCalls = apiMock.mock.calls.length;

    await user.type(screen.getByLabelText('Actor ID'), 'u-9');
    // the filter only reaches the query once it is a whole UUID
    await waitFor(() => expect(apiMock.mock.calls.length).toBe(initialCalls));
    expect(apiMock).not.toHaveBeenLastCalledWith(expect.stringContaining('actorId'));
    expect(screen.getByText('Enter the full 36-character UUID')).toBeInTheDocument();
  });

  it('debounces a complete actor UUID into the query', async () => {
    const user = userEvent.setup();
    const actorId = '9f8e7d6c-5b4a-4321-8fed-0123456789ab';
    apiMock.mockResolvedValue(page([entry()]));
    renderPage();
    await screen.findByText('u-admin');

    await user.type(screen.getByLabelText('Actor ID'), actorId);
    await waitFor(
      () =>
        expect(apiMock).toHaveBeenLastCalledWith(
          `/api/v1/admin/audit?actorId=${actorId}&page=0&size=25`,
        ),
      { timeout: 2000 },
    );
  });

  it('clears filters from the empty state', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValue(page([]));
    renderPage();
    await screen.findByText('Nothing here');

    await user.type(screen.getByLabelText('Action'), 'EXPORT');
    await waitFor(
      () =>
        expect(apiMock).toHaveBeenLastCalledWith(
          '/api/v1/admin/audit?action=EXPORT&page=0&size=25',
        ),
      { timeout: 2000 },
    );
    // filters are active, so the empty state says so instead of "no data"
    expect(await screen.findByText('No results match the current filters.')).toBeInTheDocument();

    await user.click(await screen.findByRole('button', { name: 'Clear filters' }));
    await waitFor(
      () =>
        expect(apiMock).toHaveBeenLastCalledWith('/api/v1/admin/audit?page=0&size=25'),
      { timeout: 2000 },
    );
  });
});
