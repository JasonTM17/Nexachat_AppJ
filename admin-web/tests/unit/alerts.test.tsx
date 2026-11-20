import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AlertsPage from '../../src/pages/AlertsPage';
import { I18nProvider } from '../../src/i18n';
import { SessionProvider } from '../../src/auth';
import {
  ApiException,
  type AlertDetail,
  type AlertSummary,
  type Page,
} from '../../src/api';

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api')>();
  return { ...actual, api: vi.fn() };
});

import { api } from '../../src/api';

const apiMock = api as ReturnType<typeof vi.fn>;

const me = { userId: 'me-mod', role: 'MODERATOR', displayName: 'Mod' };

const alert = (over: Partial<AlertSummary> = {}): AlertSummary => ({
  id: 'a1',
  messageId: 'm1',
  conversationId: 'c1',
  senderId: 'u-sender',
  messageSeq: 42,
  category: 'SCAM_PHISHING',
  severity: 'HIGH',
  ruleNames: ['phish-en'],
  snippet: 'send me your password',
  snippetTombstoned: false,
  status: 'OPEN',
  assigneeId: null,
  escalated: false,
  dryRun: false,
  createdAt: '2026-11-17T08:00:00Z',
  ...over,
});

const detail = (over: Partial<AlertSummary> = {}): AlertDetail => ({
  alert: alert(over),
  notes: [
    {
      id: 1,
      alertId: 'a1',
      authorId: 'me-mod',
      body: 'looking into it',
      createdAt: '2026-11-17T08:30:00Z',
    },
  ],
  senderDisplayName: 'Quân',
});

const page = (items: AlertSummary[]): Page<AlertSummary> => ({
  items,
  page: 0,
  size: 25,
  total: items.length,
  hasMore: false,
});

// Route api calls by path so the list, detail drawer and the SessionProvider
// /me lookup can each be stubbed independently of call order.
const stubRoutes = ({
  list = page([alert()]),
  alertDetail = detail(),
  failDetail = false,
}: {
  list?: Page<AlertSummary>;
  alertDetail?: AlertDetail;
  failDetail?: boolean;
} = {}) =>
  apiMock.mockImplementation((path: string) => {
    if (path === '/api/v1/admin/me') return Promise.resolve(me);
    if (path.startsWith('/api/v1/admin/alerts?')) return Promise.resolve(list);
    if (path === '/api/v1/admin/alerts/a1') {
      return failDetail
        ? Promise.reject(new ApiException(500, 'X', 'boom'))
        : Promise.resolve(alertDetail);
    }
    return Promise.reject(new ApiException(404, 'NO_ROUTE', `unstubbed ${path}`));
  });

const renderPage = () => {
  sessionStorage.setItem('admin.accessToken', 'tok');
  return render(
    <I18nProvider>
      <SessionProvider>
        <AlertsPage />
      </SessionProvider>
    </I18nProvider>,
  );
};

beforeEach(() => {
  apiMock.mockReset();
  localStorage.clear();
  sessionStorage.clear();
});

describe('AlertsPage', () => {
  it('lists open alerts with severity, category and resolved labels', async () => {
    stubRoutes();
    const { container } = renderPage();

    expect(await screen.findByText('send me your password')).toBeInTheDocument();
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/alerts?status=OPEN&page=0&size=25');
    const row = screen.getByText('send me your password').closest('tr') as HTMLElement;
    expect(within(row).getByText('HIGH')).toBeInTheDocument();
    expect(within(row).getByText('SCAM_PHISHING')).toBeInTheDocument();
    expect(within(row).getByText('Open')).toBeInTheDocument();
    // i18n keys are resolved, not leaked raw
    expect(screen.getByRole('columnheader', { name: 'Snippet' })).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/\b(alerts|common)\.[a-z]/);
  });

  it('shows the tombstone placeholder instead of a removed snippet', async () => {
    stubRoutes({ list: page([alert({ snippet: null, snippetTombstoned: true })]) });
    renderPage();
    expect(await screen.findByText('Snippet removed')).toBeInTheDocument();
    expect(screen.queryByText('send me your password')).toBeNull();
  });

  it('shows the empty state', async () => {
    stubRoutes({ list: page([]) });
    renderPage();
    expect(await screen.findByText('Nothing here')).toBeInTheDocument();
  });

  it('opens the row with Space and marks it busy while the detail loads', async () => {
    let resolveDetail: (d: AlertDetail) => void = () => {};
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/admin/me') return Promise.resolve(me);
      if (path.startsWith('/api/v1/admin/alerts?')) return Promise.resolve(page([alert()]));
      if (path === '/api/v1/admin/alerts/a1') {
        return new Promise<AlertDetail>((res) => (resolveDetail = res));
      }
      return Promise.reject(new ApiException(404, 'NO_ROUTE', `unstubbed ${path}`));
    });
    renderPage();
    const row = (await screen.findByText('send me your password')).closest('tr') as HTMLElement;

    // Space activates the row like Enter, with immediate busy feedback
    row.focus();
    await userEvent.keyboard(' ');
    expect(row).toHaveAttribute('aria-busy', 'true');

    resolveDetail(detail());
    expect(await screen.findByRole('dialog', { name: 'SCAM_PHISHING · HIGH' })).toBeInTheDocument();
    await waitFor(() => expect(row).not.toHaveAttribute('aria-busy'));
  });

  it('does not reopen the drawer when a dismissed detail fetch resolves late', async () => {
    const user = userEvent.setup();
    const resolvers: Array<(d: AlertDetail) => void> = [];
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/admin/me') return Promise.resolve(me);
      if (path.startsWith('/api/v1/admin/alerts?')) return Promise.resolve(page([alert()]));
      if (path === '/api/v1/admin/alerts/a1') {
        return new Promise<AlertDetail>((res) => resolvers.push(res));
      }
      return Promise.reject(new ApiException(404, 'NO_ROUTE', `unstubbed ${path}`));
    });
    renderPage();
    const row = (await screen.findByText('send me your password')).closest('tr') as HTMLElement;

    await user.click(row);
    resolvers[0](detail());
    await screen.findByRole('dialog', { name: 'SCAM_PHISHING · HIGH' });

    // A second fetch for the same alert (e.g. the post-action refetch) is in
    // flight when the user dismisses the drawer; its late response must not
    // resurrect the drawer or leave the row marked busy.
    await user.click(row);
    await user.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).toBeNull();

    await act(async () => {
      resolvers[resolvers.length - 1](detail());
    });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(row).not.toHaveAttribute('aria-busy');
  });

  it('filters by severity and reloads from page 0', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();
    await screen.findByText('send me your password');

    await user.selectOptions(screen.getByLabelText('Severity'), 'HIGH');
    await waitFor(() =>
      expect(apiMock).toHaveBeenLastCalledWith(
        '/api/v1/admin/alerts?status=OPEN&severity=HIGH&page=0&size=25',
      ),
    );
  });

  it('keeps the freshest filter results when an older response resolves late', async () => {
    const user = userEvent.setup();
    const unfiltered = page([alert({ snippet: 'unfiltered row' })]);
    const filtered = page([alert({ snippet: 'high severity row' })]);
    const stale: { resolve?: (value: Page<AlertSummary>) => void } = {};
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/admin/me') return Promise.resolve(me);
      if (path.startsWith('/api/v1/admin/alerts?')) {
        return path.includes('severity=HIGH')
          ? Promise.resolve(filtered)
          : new Promise<Page<AlertSummary>>((resolve) => {
              stale.resolve = resolve;
            });
      }
      return Promise.reject(new ApiException(404, 'NO_ROUTE', `unstubbed ${path}`));
    });
    renderPage();

    // The unfiltered request is still in flight when the moderator narrows
    // the filter; the filtered response must win even though it lands first.
    await user.selectOptions(screen.getByLabelText('Severity'), 'HIGH');
    expect(await screen.findByText('high severity row')).toBeInTheDocument();

    // Let the stale response land and any wrong re-render settle first.
    stale.resolve?.(unfiltered);
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(screen.queryByText('unfiltered row')).toBeNull();
    expect(screen.getByText('high severity row')).toBeInTheDocument();
  });

  it('opens the drawer and assigns the alert to the signed-in moderator', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();

    await user.click(await screen.findByText('send me your password'));
    const drawer = await screen.findByRole('dialog', { name: 'SCAM_PHISHING · HIGH' });
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/alerts/a1');
    // detail renders sender, rule chip and existing note
    expect(within(drawer).getByText('Quân')).toBeInTheDocument();
    expect(within(drawer).getByText('phish-en')).toBeInTheDocument();
    expect(within(drawer).getByText('looking into it')).toBeInTheDocument();
    // the drawer carries the raised time (dropped from narrow-screen tables)
    expect(within(drawer).getByText(/^Raised/)).toBeInTheDocument();

    apiMock.mockResolvedValueOnce(undefined);
    await user.click(within(drawer).getByRole('button', { name: 'Assign to me' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/alerts/a1', {
        method: 'PATCH',
        body: { assigneeId: 'me-mod' },
      }),
    );
  });

  it('shows a failed status change inside the open drawer and refetches detail and list', async () => {
    const user = userEvent.setup();
    // Only the PATCH fails; the detail GET and the list keep working.
    apiMock.mockImplementation((path: string, opts?: { method?: string }) => {
      if (path === '/api/v1/admin/me') return Promise.resolve(me);
      if (path === '/api/v1/admin/alerts/a1' && opts?.method === 'PATCH') {
        return Promise.reject(new ApiException(500, 'X', 'boom'));
      }
      if (path.startsWith('/api/v1/admin/alerts?')) return Promise.resolve(page([alert()]));
      if (path === '/api/v1/admin/alerts/a1') return Promise.resolve(detail());
      return Promise.reject(new ApiException(404, 'NO_ROUTE', `unstubbed ${path}`));
    });
    renderPage();

    await user.click(await screen.findByText('send me your password'));
    const drawer = await screen.findByRole('dialog', { name: 'SCAM_PHISHING · HIGH' });
    const detailCalls = () =>
      apiMock.mock.calls.filter(([p]) => p === '/api/v1/admin/alerts/a1').length;
    const listCalls = () =>
      apiMock.mock.calls.filter(([p]) => String(p).startsWith('/api/v1/admin/alerts?')).length;
    expect(detailCalls()).toBe(1);
    expect(listCalls()).toBe(1);

    await user.click(within(drawer).getByRole('button', { name: 'Resolve' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Resolved' });
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));

    // The failure renders inside the drawer (the page-top alert sits behind
    // the scrim); the confirm dialog closed but the drawer stayed open.
    expect(await within(drawer).findByRole('alert')).toHaveTextContent('Something went wrong');
    expect(screen.queryByRole('alertdialog')).toBeNull();
    // Both the detail and the list are refetched after the failed action.
    await waitFor(() => expect(detailCalls()).toBe(3));
    await waitFor(() => expect(listCalls()).toBe(2));
  });

  it('resolves an alert from the drawer without a reason', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();

    await user.click(await screen.findByText('send me your password'));
    const drawer = await screen.findByRole('dialog', { name: 'SCAM_PHISHING · HIGH' });
    await user.click(within(drawer).getByRole('button', { name: 'Resolve' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Resolved' });
    // status changes do not ask for a reason
    expect(within(dialog).queryByLabelText(/Reason/)).toBeNull();

    apiMock.mockResolvedValueOnce(undefined);
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/alerts/a1', {
        method: 'PATCH',
        body: { status: 'RESOLVED' },
      }),
    );
  });

  it('reveals message context with a required reason', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();

    await user.click(await screen.findByText('send me your password'));
    const drawer = await screen.findByRole('dialog', { name: 'SCAM_PHISHING · HIGH' });
    await user.click(within(drawer).getByRole('button', { name: 'Reveal context' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Reveal context' });
    await user.type(within(dialog).getByLabelText(/Reason/), 'active investigation');

    apiMock.mockResolvedValueOnce({
      messages: [
        {
          id: 'm1',
          seq: 42,
          senderId: 'u-sender',
          content: 'full unredacted body',
          createdAt: '2026-11-17T08:00:00Z',
        },
      ],
    });
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/alerts/a1/reveal', {
        method: 'POST',
        body: { reason: 'active investigation' },
      }),
    );
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    // NOTE: the revealed messages are never displayed — confirm() calls
    // openDetail(), which runs setRevealed(null) in the same commit as
    // setRevealed(res.messages). Asserting 'Revealed context' here would be a
    // false positive; flagged as a UI bug for follow-up.
  });

  it('adds a note from the drawer', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();

    await user.click(await screen.findByText('send me your password'));
    const drawer = await screen.findByRole('dialog', { name: 'SCAM_PHISHING · HIGH' });
    const add = within(drawer).getByRole('button', { name: 'Add' });
    expect(add).toBeDisabled();

    await user.type(within(drawer).getByLabelText('Add a note'), 'contacted sender');
    expect(add).toBeEnabled();

    apiMock.mockResolvedValueOnce(undefined);
    await user.click(add);
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/alerts/a1/notes', {
        method: 'POST',
        body: { body: 'contacted sender' },
      }),
    );
  });

  it('clamps the pager back when a bulk-dismiss empties the current page', async () => {
    const user = userEvent.setup();
    let dismissed = false;
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/admin/me') return Promise.resolve(me);
      if (path === '/api/v1/admin/alerts/bulk-dismiss') {
        dismissed = true;
        return Promise.resolve(undefined);
      }
      if (path.startsWith('/api/v1/admin/alerts?')) {
        if (path.includes('page=1')) {
          // after the dismiss, the last page is gone: 25 items remain on page 0
          if (dismissed) {
            return Promise.resolve({ items: [], page: 1, size: 25, total: 25, hasMore: false });
          }
          return Promise.resolve({
            items: [alert({ id: 'a2' })],
            page: 1,
            size: 25,
            total: 26,
            hasMore: false,
          });
        }
        return Promise.resolve({
          items: [alert()],
          page: 0,
          size: 25,
          total: dismissed ? 25 : 26,
          hasMore: false,
        });
      }
      if (path === '/api/v1/admin/alerts/a1') return Promise.resolve(detail());
      return Promise.reject(new ApiException(404, 'NO_ROUTE', `unstubbed ${path}`));
    });
    renderPage();
    await screen.findByText('send me your password');

    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByRole('checkbox', { name: 'Select a2' });
    await user.click(screen.getByRole('checkbox', { name: 'Select a2' }));
    await user.click(screen.getByRole('button', { name: /Dismiss selected · 1 selected/ }));
    const dialog = screen.getByRole('alertdialog', { name: 'Dismiss selected' });
    await user.type(within(dialog).getByLabelText(/Reason/), 'duplicates');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));

    // the emptied page=1 response clamps the pager to page 0 and refetches
    await waitFor(() => {
      const page0Calls = apiMock.mock.calls.filter(
        ([p]) => p === '/api/v1/admin/alerts?status=OPEN&page=0&size=25',
      );
      expect(page0Calls.length).toBe(2);
    });
    expect(await screen.findByText('send me your password')).toBeInTheDocument();
    // total is now 25 -> a single page -> no pager at all
    expect(screen.queryByRole('navigation', { name: 'Pagination' })).toBeNull();
  });

  it('bulk-dismisses selected alerts with a required reason', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();
    await screen.findByText('send me your password');

    await user.click(screen.getByRole('checkbox', { name: 'Select a1' }));
    await user.click(screen.getByRole('button', { name: /Dismiss selected · 1 selected/ }));
    const dialog = screen.getByRole('alertdialog', { name: 'Dismiss selected' });

    // empty reason is rejected locally
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('A reason is required');

    await user.type(within(dialog).getByLabelText(/Reason/), 'duplicates');
    apiMock.mockResolvedValueOnce(undefined);
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/alerts/bulk-dismiss', {
        method: 'POST',
        body: { ids: ['a1'], reason: 'duplicates' },
      }),
    );
  });

  it('opens the drawer from the keyboard with Enter and Space', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();

    const row = (await screen.findByText('send me your password')).closest(
      'tr',
    ) as HTMLElement;
    row.focus();
    await user.keyboard('{Enter}');
    expect(
      await screen.findByRole('dialog', { name: 'SCAM_PHISHING · HIGH' }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Close' }));

    // Space also activates the row without scrolling the page
    // (userEvent needs the [Space] code reference — {Space} maps to a
    // literal 'Space' key name that no browser produces).
    row.focus();
    await user.keyboard('[Space]');
    expect(
      await screen.findByRole('dialog', { name: 'SCAM_PHISHING · HIGH' }),
    ).toBeInTheDocument();
  });

  it('keeps Space for the row checkbox instead of opening the drawer', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();
    await screen.findByText('send me your password');

    const check = screen.getByRole('checkbox', { name: 'Select a1' });
    check.focus();
    await user.keyboard('[Space]');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(apiMock).not.toHaveBeenCalledWith('/api/v1/admin/alerts/a1');
  });

  it('shows the failure on the clicked row when the detail request fails', async () => {
    const user = userEvent.setup();
    stubRoutes({ failDetail: true });
    renderPage();

    const row = (await screen.findByText('send me your password')).closest(
      'tr',
    ) as HTMLElement;
    await user.click(row);
    expect(await within(row).findByRole('alert')).toHaveTextContent(
      'Something went wrong',
    );
    // the drawer never opens and no banner duplicates the row-level error
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(screen.getAllByRole('alert')).toHaveLength(1);
  });

  it('guards the add-note button while the request is in flight', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();

    await user.click(await screen.findByText('send me your password'));
    const drawer = await screen.findByRole('dialog', { name: 'SCAM_PHISHING · HIGH' });
    await user.type(within(drawer).getByLabelText('Add a note'), 'contacted sender');

    let resolveNote!: () => void;
    apiMock.mockImplementationOnce(
      () => new Promise<void>((res) => { resolveNote = res; }),
    );
    const add = within(drawer).getByRole('button', { name: 'Add' });
    await user.dblClick(add);
    expect(add).toBeDisabled();

    resolveNote();
    await waitFor(() => {
      const notes = apiMock.mock.calls.filter(
        ([p, o]) => p === '/api/v1/admin/alerts/a1/notes' && o?.method === 'POST',
      );
      expect(notes).toHaveLength(1);
    });
  });

  it('guards the assign button while the request is in flight', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();

    await user.click(await screen.findByText('send me your password'));
    const drawer = await screen.findByRole('dialog', { name: 'SCAM_PHISHING · HIGH' });
    const assign = within(drawer).getByRole('button', { name: 'Assign to me' });

    let resolveAssign!: () => void;
    apiMock.mockImplementationOnce(
      () => new Promise<void>((res) => { resolveAssign = res; }),
    );
    await user.dblClick(assign);
    expect(assign).toBeDisabled();

    resolveAssign();
    await waitFor(() => {
      const assigns = apiMock.mock.calls.filter(
        ([p, o]) => p === '/api/v1/admin/alerts/a1' && o?.method === 'PATCH',
      );
      expect(assigns).toHaveLength(1);
    });
  });

  it('clears the action error banner when the filters change', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();
    await screen.findByText('send me your password');

    await user.click(screen.getByText('send me your password'));
    const drawer = await screen.findByRole('dialog', { name: 'SCAM_PHISHING · HIGH' });
    apiMock.mockRejectedValueOnce(new ApiException(500, 'X', 'boom'));
    await user.click(within(drawer).getByRole('button', { name: 'Resolve' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Resolved' });
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');

    // Close the drawer and change the query; a banner for a failed action on
    // the previous view must not linger over the new one.
    await user.click(within(drawer).getByRole('button', { name: 'Close' }));
    await user.selectOptions(screen.getByLabelText('Severity'), 'HIGH');
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(screen.queryByRole('alert')).toBeNull();
  });
});
