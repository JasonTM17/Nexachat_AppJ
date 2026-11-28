import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SocialPage from '../../src/pages/SocialPage';
import { I18nProvider } from '../../src/i18n';
import { SessionProvider } from '../../src/auth';
import { ApiException, type SocialAdminState, type SocialAnnouncement } from '../../src/api';

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api')>();
  return { ...actual, api: vi.fn() };
});

import { api } from '../../src/api';

const apiMock = api as ReturnType<typeof vi.fn>;

const announcement = (over: Partial<SocialAnnouncement> = {}): SocialAnnouncement => ({
  titleVi: 'Tiêu đề VI',
  bodyVi: 'Nội dung VI',
  titleEn: 'Title EN',
  bodyEn: 'Body EN',
  ...over,
});

const socialState = (over: Partial<SocialAdminState> = {}): SocialAdminState => ({
  revision: 1,
  webEnabled: false,
  videoUploadsEnabled: false,
  draft: null,
  published: null,
  publishedAt: null,
  ...over,
});

function stubRoutes(options: {
  role?: 'ADMIN' | 'MODERATOR';
  state?: SocialAdminState;
  getError?: unknown;
} = {}) {
  const role = options.role ?? 'ADMIN';
  let current = options.state ?? socialState();
  apiMock.mockImplementation((path: string, opts?: { method?: string }) => {
    if (path === '/api/v1/admin/me') {
      return Promise.resolve({ userId: 'staff-1', role, displayName: 'Staff QA' });
    }
    if (path === '/api/v1/admin/social/settings' && (!opts || opts.method === undefined)) {
      if (options.getError) return Promise.reject(options.getError);
      return Promise.resolve(current);
    }
    return Promise.reject(new ApiException(404, 'NO_ROUTE', `unstubbed ${path}`));
  });
  return {
    setState: (next: SocialAdminState) => {
      current = next;
    },
  };
}

function renderPage({ lang }: { lang?: 'en' | 'vi' } = {}) {
  sessionStorage.setItem('admin.accessToken', 'tok');
  if (lang) localStorage.setItem('admin.lang', lang);
  return render(
    <I18nProvider>
      <SessionProvider>
        <SocialPage />
      </SessionProvider>
    </I18nProvider>,
  );
}

beforeEach(() => {
  apiMock.mockReset();
  localStorage.clear();
  sessionStorage.clear();
});

describe('SocialPage', () => {
  it('renders localized EN admin-only notice for MODERATOR without requesting settings', async () => {
    stubRoutes({ role: 'MODERATOR' });
    const { container } = renderPage({ lang: 'en' });

    expect(await screen.findByText('Admin access required')).toBeInTheDocument();
    expect(
      screen.getByText('Only administrators can view or manage Web Social settings and announcements.'),
    ).toBeInTheDocument();
    await waitFor(() => expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/me'));
    expect(apiMock).not.toHaveBeenCalledWith('/api/v1/admin/social/settings');
    expect(apiMock).not.toHaveBeenCalledWith('/api/v1/admin/social/settings', expect.anything());
    expect(screen.queryByRole('button', { name: 'Retry' })).toBeNull();
    expect(screen.queryByText('Something went wrong')).toBeNull();
    expect(container.textContent).not.toMatch(/\b(social|common)\.[a-z]/);
  });

  it('renders localized VI admin-only notice for MODERATOR without requesting settings', async () => {
    stubRoutes({ role: 'MODERATOR' });
    const { container } = renderPage({ lang: 'vi' });

    expect(await screen.findByText('Cần quyền quản trị viên')).toBeInTheDocument();
    expect(
      screen.getByText('Chỉ quản trị viên mới có thể xem hoặc quản lý cài đặt và thông báo Mạng xã hội web.'),
    ).toBeInTheDocument();
    await waitFor(() => expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/me'));
    expect(apiMock).not.toHaveBeenCalledWith('/api/v1/admin/social/settings');
    expect(screen.queryByRole('button', { name: 'Thử lại' })).toBeNull();
    expect(container.textContent).not.toMatch(/\b(social|common)\.[a-z]/);
  });

  it('loads settings for ADMIN and retries on initial failure', async () => {
    const user = userEvent.setup();
    let fail = true;
    apiMock.mockImplementation((path: string) => {
      if (path === '/api/v1/admin/me') {
        return Promise.resolve({ userId: 'staff-1', role: 'ADMIN', displayName: 'Admin QA' });
      }
      if (path === '/api/v1/admin/social/settings') {
        return fail
          ? Promise.reject(new ApiException(500, 'SERVER_ERROR', 'boom'))
          : Promise.resolve(socialState({ webEnabled: true }));
      }
      return Promise.reject(new ApiException(404, 'NO_ROUTE', `unstubbed ${path}`));
    });
    renderPage();

    expect(await screen.findByRole('button', { name: 'Retry' })).toBeInTheDocument();
    fail = false;
    await user.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByRole('heading', { name: 'Feature switches' })).toBeInTheDocument();
    expect(screen.getByRole('checkbox', { name: /Web Social feed/ })).toBeChecked();
  });

  it('toggles flags, supports cancel, and saves while preserving unsaved draft edits', async () => {
    const user = userEvent.setup();
    stubRoutes({ state: socialState({ draft: announcement() }) });
    renderPage();

    await screen.findByRole('heading', { name: 'Feature switches' });
    const saveFlagsBtn = screen.getByRole('button', { name: 'Save switches' });
    expect(saveFlagsBtn).toBeDisabled();

    const viGroup = screen.getByRole('group', { name: 'Tiếng Việt' });
    const viTitle = viGroup.querySelector('input') as HTMLInputElement;
    await user.clear(viTitle);
    await user.type(viTitle, 'Bản nháp chưa lưu');

    await user.click(screen.getByRole('checkbox', { name: /Web Social feed/ }));
    expect(saveFlagsBtn).toBeEnabled();

    // Cancel does not fire PATCH
    await user.click(saveFlagsBtn);
    const cancelDialog = screen.getByRole('alertdialog', { name: 'Apply these switches?' });
    await user.click(within(cancelDialog).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(apiMock).not.toHaveBeenCalledWith(
      '/api/v1/admin/social/settings/flags',
      expect.anything(),
    );

    // Confirm sends PATCH and preserves unsaved draft edits
    await user.click(saveFlagsBtn);
    const confirmDialog = screen.getByRole('alertdialog', { name: 'Apply these switches?' });
    apiMock.mockResolvedValueOnce(
      socialState({ revision: 2, webEnabled: true, draft: announcement() }),
    );
    await user.click(within(confirmDialog).getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/social/settings/flags', {
        method: 'PATCH',
        body: { expectedRevision: 1, webEnabled: true, videoUploadsEnabled: false },
      }),
    );
    expect(await screen.findByRole('status')).toHaveTextContent('Switches updated');
    expect(viTitle).toHaveValue('Bản nháp chưa lưu');
  });

  it('validates whitespace and over-limit draft fields with aria-invalid and toggles preview language', async () => {
    const user = userEvent.setup();
    stubRoutes({
      state: socialState({
        draft: announcement(),
        published: announcement({
          titleVi: 'Đang chạy VI',
          bodyVi: 'Nội dung live VI',
          titleEn: 'Live EN',
          bodyEn: 'Live body EN',
        }),
      }),
    });
    renderPage();

    await screen.findByRole('heading', { name: 'Live announcement' });
    const viGroup = screen.getByRole('group', { name: 'Tiếng Việt' });
    const enGroup = screen.getByRole('group', { name: 'English' });
    const viTitle = viGroup.querySelector('input') as HTMLInputElement;
    const enBody = enGroup.querySelector('textarea') as HTMLTextAreaElement;
    const saveDraftBtn = screen.getByRole('button', { name: 'Save draft' });

    // Preview toggles both draft preview and live-now copy between VI and EN
    const previewCard = document.querySelector('.social-preview-card') as HTMLElement;
    const liveCard = document.querySelector('.social-live') as HTMLElement;
    expect(previewCard).toHaveTextContent('Tiêu đề VI');
    expect(liveCard).toHaveTextContent('Đang chạy VI — Nội dung live VI');

    await user.selectOptions(screen.getByLabelText('Preview language'), 'en');
    expect(previewCard).toHaveTextContent('Title EN');
    expect(liveCard).toHaveTextContent('Live EN — Live body EN');

    // Whitespace-only disables Save draft
    await user.clear(viTitle);
    await user.type(viTitle, '   ');
    expect(saveDraftBtn).toBeDisabled();
    expect(viTitle).not.toHaveAttribute('aria-invalid');

    // Over-limit title sets aria-invalid and inline hint
    fireEvent.change(viTitle, { target: { value: 'x'.repeat(105) } });
    expect(viTitle).toHaveAttribute('aria-invalid', 'true');
    expect(within(viGroup).getByText('Exceeds the 100-character limit')).toBeInTheDocument();
    expect(saveDraftBtn).toBeDisabled();

    // Restore valid title and test over-limit body
    fireEvent.change(viTitle, { target: { value: 'Hợp lệ' } });
    expect(viTitle).not.toHaveAttribute('aria-invalid');

    fireEvent.change(enBody, { target: { value: 'b'.repeat(505) } });
    expect(enBody).toHaveAttribute('aria-invalid', 'true');
    expect(within(enGroup).getByText('Exceeds the 500-character limit')).toBeInTheDocument();
    expect(saveDraftBtn).toBeDisabled();
  });

  it('saveDraft 409 conflict preserves unsaved edits when state.draft was non-null and retries with new revision', async () => {
    const user = userEvent.setup();
    const initialDraft = announcement();
    const { setState } = stubRoutes({ state: socialState({ revision: 1, draft: initialDraft }) });
    renderPage();

    await screen.findByRole('heading', { name: 'Live announcement' });
    const viTitle = screen.getByRole('group', { name: 'Tiếng Việt' }).querySelector('input') as HTMLInputElement;
    const enBody = screen.getByRole('group', { name: 'English' }).querySelector('textarea') as HTMLTextAreaElement;

    await user.clear(viTitle);
    await user.type(viTitle, 'Tiêu đề mới chưa lưu');
    await user.clear(enBody);
    await user.type(enBody, 'Unsaved English body');

    // Simulate another admin toggling a flag (revision 1 -> 2, draft unchanged)
    setState(socialState({ revision: 2, webEnabled: true, draft: initialDraft }));
    apiMock.mockRejectedValueOnce(new ApiException(409, 'SOCIAL_SETTINGS_CONFLICT', 'stale'));
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Another admin saved changes first. The page reloaded the latest state — review and retry.',
    );
    expect(viTitle).toHaveValue('Tiêu đề mới chưa lưu');
    expect(enBody).toHaveValue('Unsaved English body');

    // Retry uses refreshed revision 2 and clears the conflict banner
    const savedDraft = announcement({ titleVi: 'Tiêu đề mới chưa lưu', bodyEn: 'Unsaved English body' });
    apiMock.mockResolvedValueOnce(
      socialState({ revision: 3, webEnabled: true, draft: savedDraft }),
    );
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/social/settings/announcement/draft', {
        method: 'PUT',
        body: {
          expectedRevision: 2,
          titleVi: 'Tiêu đề mới chưa lưu',
          bodyVi: 'Nội dung VI',
          titleEn: 'Title EN',
          bodyEn: 'Unsaved English body',
        },
      }),
    );
    expect(screen.queryByRole('alert')).toBeNull();
    expect(await screen.findByRole('status')).toHaveTextContent('Draft saved');
  });

  it('saveDraft 409 conflict preserves unsaved edits when state.draft was null', async () => {
    const user = userEvent.setup();
    const { setState } = stubRoutes({ state: socialState({ revision: 1, draft: null }) });
    renderPage();

    await screen.findByRole('heading', { name: 'Live announcement' });
    const viGroup = screen.getByRole('group', { name: 'Tiếng Việt' });
    const enGroup = screen.getByRole('group', { name: 'English' });
    await user.type(viGroup.querySelector('input') as HTMLInputElement, 'Nháp đầu tiên');
    await user.type(viGroup.querySelector('textarea') as HTMLTextAreaElement, 'Nội dung đầu tiên');
    await user.type(enGroup.querySelector('input') as HTMLInputElement, 'First draft');
    await user.type(enGroup.querySelector('textarea') as HTMLTextAreaElement, 'First body');

    setState(socialState({ revision: 2, webEnabled: true, draft: null }));
    apiMock.mockRejectedValueOnce(new ApiException(409, 'SOCIAL_SETTINGS_CONFLICT', 'stale'));
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Another admin saved changes first/);
    expect(viGroup.querySelector('input')).toHaveValue('Nháp đầu tiên');
    expect(enGroup.querySelector('textarea')).toHaveValue('First body');
  });

  it('saveDraft 409 retains local draft edit even when another admin changed the server draft', async () => {
    const user = userEvent.setup();
    const { setState } = stubRoutes({ state: socialState({ revision: 1, draft: announcement() }) });
    renderPage();

    await screen.findByRole('heading', { name: 'Live announcement' });
    const viTitle = screen.getByRole('group', { name: 'Tiếng Việt' }).querySelector('input') as HTMLInputElement;
    await user.clear(viTitle);
    await user.type(viTitle, 'Local edit');

    setState(
      socialState({
        revision: 2,
        draft: announcement({ titleVi: 'Server authoritative title' }),
      }),
    );
    apiMock.mockRejectedValueOnce(new ApiException(409, 'SOCIAL_SETTINGS_CONFLICT', 'stale'));
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Another admin saved changes first/);
    await waitFor(() => expect(viTitle).toHaveValue('Local edit'));
  });

  it('saveFlags 409 adopts authoritative server draft when local draft is untouched', async () => {
    const user = userEvent.setup();
    const { setState } = stubRoutes({ state: socialState({ revision: 1, draft: announcement() }) });
    renderPage();

    await screen.findByRole('heading', { name: 'Live announcement' });
    const viTitle = screen.getByRole('group', { name: 'Tiếng Việt' }).querySelector('input') as HTMLInputElement;

    setState(
      socialState({
        revision: 2,
        draft: announcement({ titleVi: 'Server authoritative title' }),
      }),
    );
    await user.click(screen.getByRole('checkbox', { name: /Web Social feed/ }));
    await user.click(screen.getByRole('button', { name: 'Save switches' }));
    apiMock.mockRejectedValueOnce(new ApiException(409, 'SOCIAL_SETTINGS_CONFLICT', 'stale'));
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Confirm' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/Another admin saved changes first/);
    await waitFor(() => expect(viTitle).toHaveValue('Server authoritative title'));
  });

  it('publishes, unpublishes, and handles 409 conflicts on flags/publish/unpublish', async () => {
    const user = userEvent.setup();
    const draft = announcement();
    const { setState } = stubRoutes({ state: socialState({ revision: 1, draft }) });
    renderPage();

    await screen.findByRole('heading', { name: 'Live announcement' });
    const viTitle = screen.getByRole('group', { name: 'Tiếng Việt' }).querySelector('input') as HTMLInputElement;
    await user.clear(viTitle);
    await user.type(viTitle, 'Unsaved local edit');

    // saveFlags 409 preserves draft
    setState(socialState({ revision: 2, webEnabled: true, draft }));
    await user.click(screen.getByRole('checkbox', { name: /Web Social feed/ }));
    await user.click(screen.getByRole('button', { name: 'Save switches' }));
    apiMock.mockRejectedValueOnce(new ApiException(409, 'SOCIAL_SETTINGS_CONFLICT', 'stale'));
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Another admin saved changes first/);
    expect(viTitle).toHaveValue('Unsaved local edit');

    // Publish 409 preserves draft, then happy-path publish succeeds
    setState(socialState({ revision: 3, webEnabled: true, draft }));
    await user.click(screen.getByRole('button', { name: 'Publish' }));
    apiMock.mockRejectedValueOnce(new ApiException(409, 'SOCIAL_SETTINGS_CONFLICT', 'stale'));
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull());
    expect(viTitle).toHaveValue('Unsaved local edit');

    await user.click(screen.getByRole('button', { name: 'Publish' }));
    apiMock.mockResolvedValueOnce(
      socialState({ revision: 4, webEnabled: true, draft, published: draft }),
    );
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText(/Tiêu đề VI — Nội dung VI/)).toBeInTheDocument();
    expect(viTitle).toHaveValue('Unsaved local edit');

    // Take down (unpublish) opens danger confirm and succeeds
    await user.click(screen.getByRole('button', { name: 'Take down' }));
    const unpublishDialog = screen.getByRole('alertdialog', { name: 'Take the live announcement down?' });
    const unpublishConfirm = within(unpublishDialog).getByRole('button', { name: 'Confirm' });
    expect(unpublishConfirm).toHaveClass('btn danger');
    apiMock.mockResolvedValueOnce(
      socialState({ revision: 5, webEnabled: true, draft, published: null }),
    );
    await user.click(unpublishConfirm);
    expect(await screen.findByText('Nothing is live right now.')).toBeInTheDocument();
  });

  it('shows non-409 mutation errors without reloading settings or emitting raw i18n keys', async () => {
    const user = userEvent.setup();
    stubRoutes({ state: socialState({ draft: announcement() }) });
    const { container } = renderPage();

    await screen.findByRole('heading', { name: 'Live announcement' });
    const initialGets = apiMock.mock.calls.filter(([p]) => p === '/api/v1/admin/social/settings').length;

    apiMock.mockRejectedValueOnce(new ApiException(500, 'SERVER_ERROR', 'boom'));
    await user.click(screen.getByRole('button', { name: 'Save draft' }));

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Server error 500. Try again.');
    expect(apiMock.mock.calls.filter(([p]) => p === '/api/v1/admin/social/settings').length).toBe(
      initialGets,
    );
    expect(container.textContent).not.toMatch(/\b(social|common)\.[a-z]/);
  });
});
