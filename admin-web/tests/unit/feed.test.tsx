import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import FeedPage from '../../src/pages/FeedPage';
import { I18nProvider } from '../../src/i18n';
import {
  ApiException,
  feedReportsPath,
  feedResolvePath,
  feedTargetPath,
  type FeedReport,
  type Page,
} from '../../src/api';

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api')>();
  return { ...actual, api: vi.fn() };
});

import { api } from '../../src/api';

const apiMock = api as ReturnType<typeof vi.fn>;

const report = (over: Partial<FeedReport> = {}): FeedReport => ({
  id: 'r1',
  targetType: 'POST',
  targetId: 'p1',
  reason: 'SPAM',
  details: 'looks like an ad',
  messageSnapshot: 'Buy now '.repeat(20),
  status: 'OPEN',
  createdAt: '2026-11-17T08:00:00Z',
  reporterId: 'u-reporter',
  reporterName: 'Quân',
  authorId: 'u-author',
  authorName: 'Mai',
  targetBody: 'Buy now '.repeat(20),
  targetDeleted: false,
  targetHidden: false,
  targetCreatedAt: '2026-11-17T07:00:00Z',
  ...over,
});

const page = (items: FeedReport[]): Page<FeedReport> => ({
  items,
  page: 0,
  size: 25,
  total: items.length,
  hasMore: false,
});

const renderPage = () =>
  render(
    <I18nProvider>
      <FeedPage />
    </I18nProvider>,
  );

beforeEach(() => {
  apiMock.mockReset();
  localStorage.clear();
});

describe('feed api helpers', () => {
  it('builds the report list query', () => {
    expect(feedReportsPath('OPEN', 2, 25)).toBe(
      '/api/v1/admin/feed/reports?status=OPEN&page=2&size=25',
    );
  });

  it('routes hide/unhide to posts or comments', () => {
    expect(feedTargetPath('POST', 'p1', 'hide')).toBe('/api/v1/admin/feed/posts/p1/hide');
    expect(feedTargetPath('COMMENT', 'c1', 'unhide')).toBe(
      '/api/v1/admin/feed/comments/c1/unhide',
    );
    expect(feedResolvePath('r1')).toBe('/api/v1/admin/feed/reports/r1/resolve');
  });
});

describe('FeedPage', () => {
  it('lists reports for the OPEN status with author name and no contact data', async () => {
    apiMock.mockResolvedValue(page([report()]));
    renderPage();
    expect(await screen.findByText('Mai')).toBeInTheDocument();
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/feed/reports?status=OPEN&page=0&size=25');
    // The reason shows in its column and repeats in the mobile-only meta
    // block (CSS keeps one visible per viewport).
    expect(screen.getAllByText('SPAM')).toHaveLength(2);
    expect(screen.getByRole('button', { name: 'Hide' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dismiss' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Mark reviewed' })).toBeInTheDocument();
  });

  it('falls back to the author id when there is no display name', async () => {
    apiMock.mockResolvedValue(page([report({ authorName: null })]));
    renderPage();
    expect(await screen.findByText('u-author')).toBeInTheDocument();
  });

  it('falls back to the target body when the snapshot is missing', async () => {
    apiMock.mockResolvedValue(
      page([report({ messageSnapshot: null, targetBody: 'live body' })]),
    );
    renderPage();
    expect(await screen.findByText('live body')).toBeInTheDocument();
  });

  it('shows the empty state', async () => {
    apiMock.mockResolvedValue(page([]));
    renderPage();
    expect(await screen.findByText('Nothing here')).toBeInTheDocument();
  });

  it('shows an error block and retries', async () => {
    const user = userEvent.setup();
    apiMock.mockRejectedValueOnce(new ApiException(500, 'X', 'boom'));
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
    apiMock.mockResolvedValueOnce(page([report()]));
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('Mai')).toBeInTheDocument();
  });

  it('expands and collapses the full snapshot', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValue(page([report()]));
    renderPage();
    const toggle = await screen.findByRole('button', { name: 'Show full' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);
    expect(screen.getByRole('button', { name: 'Show less' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    expect(screen.getByText('looks like an ad')).toBeInTheDocument();
  });

  it('hides a post with a required reason, then reloads', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValue(page([report()]));
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Hide' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Hide post' });
    expect(within(dialog).getByLabelText(/Reason/)).toHaveAttribute('maxlength', '500');

    // empty reason is rejected locally
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    expect(apiMock).toHaveBeenCalledTimes(1);

    await user.type(within(dialog).getByLabelText(/Reason/), 'spam ad');
    apiMock.mockResolvedValue(undefined);
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/feed/posts/p1/hide', {
        method: 'POST',
        body: { reason: 'spam ad' },
      }),
    );
    await waitFor(() => expect(apiMock).toHaveBeenCalledTimes(3));
    expect(screen.queryByRole('alertdialog')).toBeNull();
  });

  it('hides a comment through the comments endpoint', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValue(
      page([report({ targetType: 'COMMENT', targetId: 'c9' })]),
    );
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Hide' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Hide comment' });
    await user.type(within(dialog).getByLabelText(/Reason/), 'abuse');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/feed/comments/c9/hide', {
        method: 'POST',
        body: { reason: 'abuse' },
      }),
    );
  });

  it('unhides a hidden target without a dialog', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValue(page([report({ targetHidden: true })]));
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Unhide' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/feed/posts/p1/unhide', {
        method: 'POST',
      }),
    );
    expect(screen.queryByRole('button', { name: 'Hide' })).toBeNull();
  });

  it('offers no hide action for a deleted target and none once resolved', async () => {
    apiMock.mockResolvedValue(
      page([report({ targetDeleted: true, status: 'REVIEWED' })]),
    );
    renderPage();
    await screen.findByText('Mai');
    expect(screen.queryByRole('button', { name: 'Hide' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Unhide' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Dismiss' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Mark reviewed' })).toBeNull();
  });

  it('resolves a report as reviewed and as dismissed after confirmation', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValue(page([report()]));
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Mark reviewed' }));
    // the irreversible status change asks first
    const reviewDialog = screen.getByRole('alertdialog', { name: 'Mark reviewed' });
    await user.click(within(reviewDialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/feed/reports/r1/resolve', {
        method: 'POST',
        body: { status: 'REVIEWED' },
      }),
    );

    await user.click(await screen.findByRole('button', { name: 'Dismiss' }));
    const dismissDialog = screen.getByRole('alertdialog', { name: 'Dismiss' });
    expect(
      within(dismissDialog).getByRole('button', { name: 'Confirm' }),
    ).toHaveClass('btn danger');
    await user.click(within(dismissDialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/feed/reports/r1/resolve', {
        method: 'POST',
        body: { status: 'DISMISSED' },
      }),
    );
  });

  it('cancelling the resolve confirmation sends no request', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValue(page([report()]));
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Dismiss' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Dismiss' });
    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(apiMock).not.toHaveBeenCalledWith(
      '/api/v1/admin/feed/reports/r1/resolve',
      expect.anything(),
    );
  });

  it('shows an error when an action fails', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValueOnce(page([report()]));
    renderPage();
    apiMock.mockRejectedValueOnce(new ApiException(404, 'POST_NOT_FOUND', 'gone'));
    await user.click(await screen.findByRole('button', { name: 'Mark reviewed' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Mark reviewed' });
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
  });

  it('clamps the pager back when hiding the last report of a page', async () => {
    const user = userEvent.setup();
    let hidden = false;
    apiMock.mockImplementation((path: string) => {
      if (path === feedTargetPath('POST', 'p2', 'hide')) {
        hidden = true;
        return Promise.resolve(undefined);
      }
      if (path.startsWith('/api/v1/admin/feed/reports?')) {
        if (path.includes('page=1')) {
          // after the hide, the last page is gone: 25 remain on page 0
          if (hidden) {
            return Promise.resolve({ items: [], page: 1, size: 25, total: 25, hasMore: false });
          }
          return Promise.resolve({
            items: [report({ id: 'r2', targetId: 'p2' })],
            page: 1,
            size: 25,
            total: 26,
            hasMore: false,
          });
        }
        return Promise.resolve({
          items: [report()],
          page: 0,
          size: 25,
          total: hidden ? 25 : 26,
          hasMore: false,
        });
      }
      return Promise.reject(new ApiException(404, 'NO_ROUTE', `unstubbed ${path}`));
    });
    renderPage();
    await screen.findByText('Mai');

    await user.click(screen.getByRole('button', { name: 'Next' }));
    await user.click(await screen.findByRole('button', { name: 'Hide' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Hide post' });
    await user.type(within(dialog).getByLabelText(/Reason/), 'spam');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));

    // the emptied page=1 response clamps the pager to page 0 and refetches
    await waitFor(() => {
      const page0Calls = apiMock.mock.calls.filter(
        ([p]) => p === '/api/v1/admin/feed/reports?status=OPEN&page=0&size=25',
      );
      expect(page0Calls.length).toBe(2);
    });
    expect(await screen.findByText('Mai')).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Pagination' })).toBeNull();
  });

  it('clears the action error banner after a successful reload', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValueOnce(page([report()]));
    renderPage();
    expect(await screen.findByText('Mai')).toBeInTheDocument();

    apiMock.mockRejectedValueOnce(new ApiException(500, 'X', 'boom'));
    await user.click(screen.getByRole('button', { name: 'Mark reviewed' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Mark reviewed' });
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');

    // The next successful list load means the banner no longer describes the
    // view on screen, so it must not linger over fresh data.
    apiMock.mockResolvedValue(page([report({ status: 'DISMISSED', authorName: 'Lan' })]));
    await user.selectOptions(screen.getByLabelText('Status'), 'DISMISSED');
    expect(await screen.findByText('Lan')).toBeInTheDocument();
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('switches the status filter and reloads from page 0', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValue(page([report()]));
    renderPage();
    await screen.findByText('Mai');
    await user.selectOptions(screen.getByLabelText('Status'), 'DISMISSED');
    await waitFor(() =>
      expect(apiMock).toHaveBeenLastCalledWith(
        '/api/v1/admin/feed/reports?status=DISMISSED&page=0&size=25',
      ),
    );
  });

  it('keeps the stale list while a filter refetch is in flight', async () => {
    const user = userEvent.setup();
    let resolveSecond: (value: Page<FeedReport>) => void = () => {};
    apiMock.mockImplementationOnce(() => Promise.resolve(page([report()])));
    apiMock.mockImplementationOnce(
      () => new Promise<Page<FeedReport>>((res) => (resolveSecond = res)),
    );
    renderPage();
    await screen.findByText('Mai');

    await user.selectOptions(screen.getByLabelText('Status'), 'DISMISSED');
    // the previous list stays instead of flashing the loading state
    expect(screen.getByText('Mai')).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).toBeNull();

    resolveSecond(page([report({ id: 'r2', status: 'DISMISSED', authorName: 'Lan' })]));
    expect(await screen.findByText('Lan')).toBeInTheDocument();
  });

  it('keeps the freshest status results when an older response resolves late', async () => {
    const user = userEvent.setup();
    const stale: { resolve?: (value: Page<FeedReport>) => void } = {};
    apiMock.mockImplementation((path: string) => {
      if (path.startsWith('/api/v1/admin/feed/reports?')) {
        return path.includes('status=DISMISSED')
          ? Promise.resolve(page([report({ id: 'r2', authorName: 'Lan' })]))
          : new Promise<Page<FeedReport>>((resolve) => {
              stale.resolve = resolve;
            });
      }
      return Promise.reject(new ApiException(404, 'NO_ROUTE', `unstubbed ${path}`));
    });
    renderPage();

    // The OPEN request is still in flight when the status changes; the
    // DISMISSED response must win even though it lands first.
    await user.selectOptions(screen.getByLabelText('Status'), 'DISMISSED');
    expect(await screen.findByText('Lan')).toBeInTheDocument();

    // Let the stale response land and any wrong re-render settle first.
    stale.resolve?.(page([report()]));
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(screen.queryByText('Mai')).toBeNull();
    expect(screen.getByText('Lan')).toBeInTheDocument();
  });

  it('closes the expanded detail of a same-id report after the status filter changes', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValueOnce(page([report({ id: 'r1', details: 'open-view details' })]));
    renderPage();
    const toggle = await screen.findByRole('button', { name: 'Show full' });
    await user.click(toggle);
    expect(screen.getByRole('button', { name: 'Show less' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );
    expect(screen.getByText('open-view details')).toBeInTheDocument();

    apiMock.mockResolvedValue(
      page([
        report({
          id: 'r1',
          status: 'DISMISSED',
          details: 'dismissed-view details',
          authorName: 'Lan',
        }),
      ]),
    );
    await user.selectOptions(screen.getByLabelText('Status'), 'DISMISSED');
    await screen.findByText('Lan');
    const nextToggle = screen.getByRole('button', { name: 'Show full' });
    expect(nextToggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: 'Show less' })).toBeNull();
    expect(screen.queryByText('dismissed-view details')).toBeNull();
  });

  it('closes the expanded detail of a same-id report after the page changes', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValueOnce({ ...page([report({ id: 'r1' })]), total: 50 });
    renderPage();
    const toggle = await screen.findByRole('button', { name: 'Show full' });
    await user.click(toggle);
    expect(screen.getByRole('button', { name: 'Show less' })).toHaveAttribute(
      'aria-expanded',
      'true',
    );

    apiMock.mockResolvedValue({
      ...page([
        report({ id: 'r1', details: 'page-two details', authorName: 'Lan' }),
      ]),
      page: 1,
      total: 50,
    });
    await user.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('Lan');
    const nextToggle = screen.getByRole('button', { name: 'Show full' });
    expect(nextToggle).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: 'Show less' })).toBeNull();
    expect(screen.queryByText('page-two details')).toBeNull();
  });

  it('steps back to the last non-empty page when the current page empties', async () => {
    const user = userEvent.setup();
    // Two pages of open reports; resolving the last one on the final page
    // shrinks the list while the pager sits on that now-empty page.
    apiMock.mockImplementation((path: string) =>
      Promise.resolve(
        path.includes('page=1')
          ? { ...page([]), total: 25 }
          : { ...page([report()]), total: 26 },
      ),
    );
    renderPage();
    await screen.findByText('Mai');
    await user.click(screen.getByRole('button', { name: 'Next' }));

    // page 1 came back empty: the clamp reloads page 0 instead of stranding
    // the pager on an empty "Nothing here" screen.
    await waitFor(() =>
      expect(apiMock).toHaveBeenLastCalledWith(
        '/api/v1/admin/feed/reports?status=OPEN&page=0&size=25',
      ),
    );
    expect(await screen.findByText('Mai')).toBeInTheDocument();
  });

  it('keeps the queue visible with an inline banner when an action fails', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValueOnce(page([report()]));
    renderPage();
    await screen.findByText('Mai');

    apiMock.mockRejectedValueOnce(new ApiException(500, 'X', 'boom'));
    await user.click(screen.getByRole('button', { name: 'Mark reviewed' }));
    const reviewDialog = screen.getByRole('alertdialog', { name: 'Mark reviewed' });
    await user.click(within(reviewDialog).getByRole('button', { name: 'Confirm' }));

    // the action failure gets a banner, not a full-screen block
    expect(await screen.findAllByRole('alert')).toHaveLength(1);
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.getByText('Mai')).toBeInTheDocument();

    // a list failure still owns the view: the block replaces the queue and
    // the action banner makes way so only one error surface shows.
    apiMock.mockRejectedValueOnce(new ApiException(500, 'X', 'boom'));
    await user.selectOptions(screen.getByLabelText('Status'), 'DISMISSED');
    await waitFor(() => expect(screen.getAllByRole('alert')).toHaveLength(1));
    expect(screen.queryByText('Mai')).toBeNull();
  });
});
