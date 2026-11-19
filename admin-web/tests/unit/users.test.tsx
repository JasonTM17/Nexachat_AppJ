import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import UsersPage from '../../src/pages/UsersPage';
import { I18nProvider } from '../../src/i18n';
import { SessionProvider } from '../../src/auth';
import {
  ApiException,
  type Page,
  type UserDetail,
  type UserSummary,
} from '../../src/api';

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api')>();
  return { ...actual, api: vi.fn() };
});

import { api } from '../../src/api';

const apiMock = api as ReturnType<typeof vi.fn>;

const me = { userId: 'me-admin', role: 'ADMIN', displayName: 'Root' };

const user = (over: Partial<UserSummary> = {}): UserSummary => ({
  id: 'u1',
  phone: '+84901234567',
  displayName: 'Mai',
  avatarUrl: null,
  email: null,
  role: 'USER',
  suspendedAt: null,
  suspendedReason: null,
  createdAt: '2026-10-01T08:00:00Z',
  lastSeenAt: '2026-11-17T07:30:00Z',
  deletedAt: null,
  ...over,
});

const detail = (over: Partial<UserSummary> = {}): UserDetail => ({
  user: user(over),
  deviceCount: 2,
  sessionCount: 3,
  conversationCount: 4,
  alertCount: 1,
});

const page = (items: UserSummary[]): Page<UserSummary> => ({
  items,
  page: 0,
  size: 25,
  total: items.length,
  hasMore: false,
});

// Route api calls by path so the page's list/detail/deleted endpoints and the
// SessionProvider /me lookup can each be stubbed independently of call order.
// NOTE: UsersPage refetches continuously (its `latest` callback identity is
// unstable), so one-shot mocks are unsafe — every path needs a stable route.
const stubRoutes = ({
  list = page([user()]),
  userDetail = detail(),
  deleted = [] as UserSummary[],
  fail = [] as string[],
}: {
  list?: Page<UserSummary>;
  userDetail?: UserDetail;
  deleted?: UserSummary[];
  fail?: string[];
} = {}) =>
  apiMock.mockImplementation((path: string) => {
    if (fail.includes(path)) {
      return Promise.reject(new ApiException(500, 'X', 'boom'));
    }
    if (path === '/api/v1/admin/me') return Promise.resolve(me);
    if (path === '/api/v1/admin/users/deleted?limit=50') {
      return Promise.resolve(deleted);
    }
    if (path.startsWith('/api/v1/admin/users?')) return Promise.resolve(list);
    if (path.startsWith('/api/v1/admin/users/')) return Promise.resolve(userDetail);
    return Promise.reject(new ApiException(404, 'NO_ROUTE', `unstubbed ${path}`));
  });

const renderPage = ({ withSession = true } = {}) => {
  if (withSession) sessionStorage.setItem('admin.accessToken', 'tok');
  return render(
    <I18nProvider>
      <SessionProvider>
        <UsersPage />
      </SessionProvider>
    </I18nProvider>,
  );
};

beforeEach(() => {
  apiMock.mockReset();
  localStorage.clear();
  sessionStorage.clear();
});

describe('UsersPage', () => {
  it('lists users with name, role and resolved status labels', async () => {
    stubRoutes({ list: page([user(), user({ id: 'u2', displayName: null, phone: null, suspendedAt: '2026-11-01T00:00:00Z' })]) });
    const { container } = renderPage();

    const row = (await screen.findByText('Mai')).closest('tr') as HTMLElement;
    const rows = container.querySelectorAll('tbody tr');
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/users?page=0&size=25');
    expect(within(row).getByText('+84901234567')).toBeInTheDocument();
    expect(within(row).getByText('USER')).toBeInTheDocument();
    expect(within(row).getByText('Active')).toBeInTheDocument();

    // displayName/phone fall back to a dash, suspended status resolves
    const row2 = rows[1] as HTMLElement;
    expect(within(row2).getByText('Suspended')).toBeInTheDocument();
    expect(within(row2).getAllByText('—').length).toBeGreaterThan(0);
    // i18n keys are resolved, not leaked raw
    expect(screen.getByRole('columnheader', { name: 'User' })).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/\b(users|common)\.[a-z]/);
  });

  it('shows the empty state', async () => {
    stubRoutes({ list: page([]) });
    renderPage();
    expect(await screen.findByText('Nothing here')).toBeInTheDocument();
  });

  it('shows an action error inside the open drawer and refetches the detail', async () => {
    const userEvt = userEvent.setup();
    stubRoutes({ fail: ['/api/v1/admin/users/u1/suspend'] });
    renderPage();

    await userEvt.click(await screen.findByText('Mai'));
    const drawer = await screen.findByRole('dialog', { name: 'Mai' });
    const detailCalls = () =>
      apiMock.mock.calls.filter(([p]) => p === '/api/v1/admin/users/u1').length;
    expect(detailCalls()).toBe(1);

    await userEvt.click(within(drawer).getByRole('button', { name: 'Suspend' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Suspend' });
    await userEvt.type(within(dialog).getByLabelText(/Reason/), 'spam farm');
    await userEvt.click(within(dialog).getByRole('button', { name: 'Confirm' }));

    // The failure renders inside the drawer (the page-top alert sits behind
    // the scrim); the confirm dialog closed but the drawer stayed open.
    expect(await within(drawer).findByRole('alert')).toHaveTextContent('Something went wrong');
    expect(screen.queryByRole('alertdialog')).toBeNull();
    // The detail is refetched after the failed action instead of going stale.
    await waitFor(() => expect(detailCalls()).toBeGreaterThan(1));
  });

  it('opens the detail drawer and suspends with a required reason', async () => {
    const userEvt = userEvent.setup();
    stubRoutes();
    renderPage();

    await userEvt.click(await screen.findByText('Mai'));
    const drawer = await screen.findByRole('dialog', { name: 'Mai' });
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/users/u1');
    expect(within(drawer).getByText('Sessions')).toBeInTheDocument();
    expect(within(drawer).getByText('3')).toBeInTheDocument();

    await userEvt.click(within(drawer).getByRole('button', { name: 'Suspend' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Suspend' });

    // empty reason is rejected locally, no POST is made
    await userEvt.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A reason is required');
    expect(apiMock).not.toHaveBeenCalledWith(
      '/api/v1/admin/users/u1/suspend',
      expect.anything(),
    );

    await userEvt.type(within(dialog).getByLabelText(/Reason/), 'spam farm');
    await userEvt.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/users/u1/suspend', {
        method: 'POST',
        body: { reason: 'spam farm' },
      }),
    );
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
  });

  it('revokes sessions without requiring a reason', async () => {
    const userEvt = userEvent.setup();
    stubRoutes();
    renderPage();

    await userEvt.click(await screen.findByText('Mai'));
    const drawer = await screen.findByRole('dialog', { name: 'Mai' });
    await userEvt.click(within(drawer).getByRole('button', { name: 'Revoke sessions' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Revoke sessions' });
    expect(within(dialog).queryByLabelText(/Reason/)).toBeNull();
    await userEvt.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith(
        '/api/v1/admin/users/u1/revoke-sessions',
        expect.objectContaining({ method: 'POST' }),
      ),
    );
  });

  it('hides the role picker from non-admin moderators', async () => {
    stubRoutes();
    renderPage({ withSession: false });

    await userEvent.setup().click(await screen.findByText('Mai'));
    const drawer = await screen.findByRole('dialog', { name: 'Mai' });
    expect(within(drawer).getByRole('button', { name: 'Suspend' })).toBeInTheDocument();
    expect(within(drawer).queryByLabelText('Change role')).toBeNull();
  });

  it('opens the row with Space and marks it busy while the detail loads', async () => {
    let resolveDetail: (d: UserDetail) => void = () => {};
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/admin/me') return Promise.resolve(me);
      if (path === '/api/v1/admin/users/deleted?limit=50') return Promise.resolve([]);
      if (path.startsWith('/api/v1/admin/users?')) return Promise.resolve(page([user()]));
      if (path === '/api/v1/admin/users/u1') {
        return new Promise<UserDetail>((res) => (resolveDetail = res));
      }
      return Promise.reject(new ApiException(404, 'NO_ROUTE', `unstubbed ${path}`));
    });
    renderPage();
    const row = (await screen.findByText('Mai')).closest('tr') as HTMLElement;

    // Space activates the row like Enter, with immediate busy feedback
    row.focus();
    await userEvent.keyboard(' ');
    expect(row).toHaveAttribute('aria-busy', 'true');

    resolveDetail(detail());
    expect(await screen.findByRole('dialog', { name: 'Mai' })).toBeInTheDocument();
    await waitFor(() => expect(row).not.toHaveAttribute('aria-busy'));
  });

  it('filters by status and reloads from page 0', async () => {
    const userEvt = userEvent.setup();
    stubRoutes();
    renderPage();
    await screen.findByText('Mai');

    await userEvt.selectOptions(screen.getByLabelText('Status'), 'suspended');
    await waitFor(() =>
      expect(apiMock).toHaveBeenLastCalledWith(
        '/api/v1/admin/users?status=suspended&page=0&size=25',
      ),
    );
  });

  it('lists deleted accounts on toggle', async () => {
    const userEvt = userEvent.setup();
    stubRoutes({ deleted: [user({ id: 'u9', displayName: 'Gone', deletedAt: '2026-11-10T00:00:00Z' })] });
    renderPage();
    await screen.findByText('Mai');

    await userEvt.click(screen.getByRole('button', { name: 'Deleted accounts' }));
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/users/deleted?limit=50');
    const row = (await screen.findByText('Gone')).closest('tr') as HTMLElement;
    expect(within(row).getByText('Deleted')).toBeInTheDocument();
  });

  it('shows the failure on the clicked row when the detail request fails', async () => {
    const userEvt = userEvent.setup();
    stubRoutes({ fail: ['/api/v1/admin/users/u1'] });
    renderPage();

    const row = (await screen.findByText('Mai')).closest('tr') as HTMLElement;
    await userEvt.click(row);
    expect(await within(row).findByRole('alert')).toHaveTextContent('Something went wrong');
    // the drawer never opens and no banner duplicates the row-level error
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });

  it('opens the drawer from the keyboard with Enter and Space', async () => {
    const userEvt = userEvent.setup();
    stubRoutes();
    renderPage();

    const row = (await screen.findByText('Mai')).closest('tr') as HTMLElement;
    row.focus();
    await userEvt.keyboard('{Enter}');
    const drawer = await screen.findByRole('dialog', { name: 'Mai' });
    // the drawer carries the fields dropped from narrow-screen tables
    expect(within(drawer).getByText('Joined')).toBeInTheDocument();
    expect(within(drawer).getByText('Last seen')).toBeInTheDocument();
    await userEvt.click(screen.getByRole('button', { name: 'Close' }));

    // Space also activates the row (userEvent needs the [Space] code
    // reference — {Space} maps to a literal 'Space' key name)
    row.focus();
    await userEvt.keyboard('[Space]');
    expect(await screen.findByRole('dialog', { name: 'Mai' })).toBeInTheDocument();
  });
});
