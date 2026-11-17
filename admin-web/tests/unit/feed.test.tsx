import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
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
    expect(screen.getByText('SPAM')).toBeInTheDocument();
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

  it('resolves a report as reviewed and as dismissed', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValue(page([report()]));
    renderPage();
    await user.click(await screen.findByRole('button', { name: 'Mark reviewed' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/feed/reports/r1/resolve', {
        method: 'POST',
        body: { status: 'REVIEWED' },
      }),
    );
    await user.click(await screen.findByRole('button', { name: 'Dismiss' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/feed/reports/r1/resolve', {
        method: 'POST',
        body: { status: 'DISMISSED' },
      }),
    );
  });

  it('shows an error when an action fails', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValueOnce(page([report()]));
    renderPage();
    apiMock.mockRejectedValueOnce(new ApiException(404, 'POST_NOT_FOUND', 'gone'));
    await user.click(await screen.findByRole('button', { name: 'Mark reviewed' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong');
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
});
