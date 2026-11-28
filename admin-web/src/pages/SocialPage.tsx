import { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiException, type SocialAdminState, type SocialAnnouncement } from '../api';
import { useSession } from '../auth';
import { useI18n } from '../i18n';
import {
  Chip,
  ConfirmDialog,
  ErrorBlock,
  Loading,
  Toast,
  useApiErrorMessage,
  useToast,
} from '../components/ui';

const TITLE_MAX = 100;
const BODY_MAX = 500;
const EMPTY_DRAFT: SocialAnnouncement = { titleVi: '', bodyVi: '', titleEn: '', bodyEn: '' };

function sameDraft(left: SocialAnnouncement | null, right: SocialAnnouncement | null): boolean {
  if (left === null || right === null) return left === right;
  return left.titleVi === right.titleVi && left.bodyVi === right.bodyVi
    && left.titleEn === right.titleEn && left.bodyEn === right.bodyEn;
}

type PendingAction =
  | { kind: 'flags'; webEnabled: boolean; videoUploadsEnabled: boolean }
  | { kind: 'publish' }
  | { kind: 'unpublish' };

/**
 * ADMIN-only runtime controls for the web Social surface plus the live
 * announcement CMS. Every mutation sends the revision the page loaded; a
 * conflict means someone else saved first, so the page reloads instead of
 * overwriting. Flag flips take effect on the next API request — there is no
 * client-side cache to invalidate.
 */
export default function SocialPage() {
  const { t } = useI18n();
  const { isAdmin, loading: sessionLoading } = useSession();
  const formatError = useApiErrorMessage();
  const toast = useToast();

  const [state, setState] = useState<SocialAdminState | null>(null);
  const [failed, setFailed] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [actionError, setActionError] = useState<unknown>(null);

  const [webEnabled, setWebEnabled] = useState(false);
  const [videoEnabled, setVideoEnabled] = useState(false);
  const [flagsBusy, setFlagsBusy] = useState(false);

  const [draftForm, setDraftForm] = useState(EMPTY_DRAFT);
  const [draftBusy, setDraftBusy] = useState(false);
  const [previewLang, setPreviewLang] = useState<'vi' | 'en'>('vi');

  const [pending, setPending] = useState<PendingAction | null>(null);
  const [pendingBusy, setPendingBusy] = useState(false);

  // Mirror of the in-progress form state so async conflict reloads compare
  // against what the admin is editing right now, not a stale render closure.
  const formRef = useRef({ draftForm, webEnabled, videoEnabled });
  formRef.current = { draftForm, webEnabled, videoEnabled };

  const updateSettings = useCallback((next: SocialAdminState) => {
    setState(next);
    setWebEnabled(next.webEnabled);
    setVideoEnabled(next.videoUploadsEnabled);
  }, []);

  const adopt = useCallback((next: SocialAdminState) => {
    updateSettings(next);
    const draft = next.draft ?? EMPTY_DRAFT;
    setDraftForm({ titleVi: draft.titleVi, bodyVi: draft.bodyVi, titleEn: draft.titleEn, bodyEn: draft.bodyEn });
  }, [updateSettings]);

  // Conflict reloads must never throw away input the admin is still
  // editing. Per field: whenever the local value differs from the pre-
  // mutation baseline it carries admin intent and is kept; untouched fields
  // adopt the authoritative server value so the console never drifts stale.
  const load = useCallback((before?: SocialAdminState) => {
    setFailed(false);
    api<SocialAdminState>('/api/v1/admin/social/settings')
      .then((next) => {
        if (!before) {
          adopt(next);
          return;
        }
        setState(next);
        const current = formRef.current;
        if (current.webEnabled === before.webEnabled) setWebEnabled(next.webEnabled);
        if (current.videoEnabled === before.videoUploadsEnabled) setVideoEnabled(next.videoUploadsEnabled);
        const baseDraft = before.draft ?? EMPTY_DRAFT;
        if (sameDraft(current.draftForm, baseDraft)) {
          const draft = next.draft ?? EMPTY_DRAFT;
          setDraftForm({ titleVi: draft.titleVi, bodyVi: draft.bodyVi, titleEn: draft.titleEn, bodyEn: draft.bodyEn });
        }
      })
      .catch(() => setFailed(true));
  }, [adopt]);

  useEffect(() => {
    if (!sessionLoading && isAdmin) load();
  }, [sessionLoading, isAdmin, load]);

  // A stale revision means another admin saved first: never overwrite their
  // change — surface the conflict and reload the authoritative state.
  const handleMutationError = useCallback((err: unknown, before?: SocialAdminState) => {
    if (err instanceof ApiException && err.code === 'SOCIAL_SETTINGS_CONFLICT') {
      setConflict(true);
      load(before);
      return;
    }
    setActionError(err);
  }, [load]);

  const saveFlags = async (web: boolean, video: boolean) => {
    if (!state) return;
    setFlagsBusy(true);
    setActionError(null);
    try {
      updateSettings(await api<SocialAdminState>('/api/v1/admin/social/settings/flags', {
        method: 'PATCH',
        body: { expectedRevision: state.revision, webEnabled: web, videoUploadsEnabled: video },
      }));
      setConflict(false);
      toast.show(t('social.flags.saved'));
    } catch (err) {
      handleMutationError(err, state);
    } finally {
      setFlagsBusy(false);
      setPendingBusy(false);
      setPending(null);
    }
  };

  const saveDraft = async () => {
    if (!state) return;
    setDraftBusy(true);
    setActionError(null);
    try {
      adopt(await api<SocialAdminState>('/api/v1/admin/social/settings/announcement/draft', {
        method: 'PUT',
        body: { expectedRevision: state.revision, ...draftForm },
      }));
      setConflict(false);
      toast.show(t('social.cms.draftSaved'));
    } catch (err) {
      handleMutationError(err, state);
    } finally {
      setDraftBusy(false);
    }
  };

  const runPending = async () => {
    if (!state || !pending) return;
    setPendingBusy(true);
    setActionError(null);
    const body = { expectedRevision: state.revision };
    try {
      if (pending.kind === 'flags') {
        await saveFlags(pending.webEnabled, pending.videoUploadsEnabled);
        return;
      }
      updateSettings(await api<SocialAdminState>(
        `/api/v1/admin/social/settings/announcement/${pending.kind}`,
        { method: 'POST', body },
      ));
      setConflict(false);
      toast.show(t(pending.kind === 'publish' ? 'social.cms.published' : 'social.cms.unpublished'));
    } catch (err) {
      handleMutationError(err, state);
    } finally {
      setPendingBusy(false);
      setPending(null);
    }
  };

  if (sessionLoading) return <Loading />;
  if (!isAdmin) {
    return (
      <div className="empty" role="status">
        <div className="state-icon" aria-hidden="true">!</div>
        <p className="state-title">{t('social.adminOnly.title')}</p>
        <p className="state-hint">{t('social.adminOnly.hint')}</p>
      </div>
    );
  }
  if (failed) return <ErrorBlock onRetry={() => load()} />;
  if (!state) return <Loading />;

  const flagsDirty = webEnabled !== state.webEnabled || videoEnabled !== state.videoUploadsEnabled;
  const draftValid = draftForm.titleVi.trim() && draftForm.bodyVi.trim()
    && draftForm.titleEn.trim() && draftForm.bodyEn.trim()
    && draftForm.titleVi.trim().length <= TITLE_MAX && draftForm.titleEn.trim().length <= TITLE_MAX
    && draftForm.bodyVi.trim().length <= BODY_MAX && draftForm.bodyEn.trim().length <= BODY_MAX;
  const previewTitle = previewLang === 'vi' ? draftForm.titleVi : draftForm.titleEn;
  const previewBody = previewLang === 'vi' ? draftForm.bodyVi : draftForm.bodyEn;

  return (
    <>
      <div className="social-stack">
        {conflict && (
          <div className="social-banner is-warn" role="alert">
            {t('social.conflict')}
          </div>
        )}
        {actionError != null && (
          <div className="social-banner is-error" role="alert">
            {formatError(actionError)}
          </div>
        )}

        <section className="card" aria-labelledby="social-flags-title">
          <h2 id="social-flags-title">{t('social.flags.title')}</h2>
          <p className="page-blurb">{t('social.flags.blurb')}</p>
          <label className="check-row">
            <input
              type="checkbox"
              checked={webEnabled}
              onChange={(e) => setWebEnabled(e.target.checked)}
            />
            <span>
              {t('social.flags.web')}
              <Chip tone={state.webEnabled ? 'success' : undefined}>
                {state.webEnabled ? t('common.on') : t('common.off')}
              </Chip>
            </span>
          </label>
          <label className="check-row">
            <input
              type="checkbox"
              checked={videoEnabled}
              onChange={(e) => setVideoEnabled(e.target.checked)}
            />
            <span>
              {t('social.flags.video')}
              <Chip tone={state.videoUploadsEnabled ? 'success' : undefined}>
                {state.videoUploadsEnabled ? t('common.on') : t('common.off')}
              </Chip>
            </span>
          </label>
          <button
            className="btn primary"
            disabled={!flagsDirty || flagsBusy || pendingBusy}
            onClick={() => setPending({ kind: 'flags', webEnabled, videoUploadsEnabled: videoEnabled })}
          >
            {t('social.flags.save')}
          </button>
        </section>

        <section className="card" aria-labelledby="social-cms-title">
          <h2 id="social-cms-title">{t('social.cms.title')}</h2>
          <p className="page-blurb">{t('social.cms.blurb')}</p>

          {state.published ? (
            <div className="social-live">
              <h3>{t('social.cms.liveNow')}</h3>
              <p className="state-hint">
                {previewLang === 'vi' ? state.published.titleVi : state.published.titleEn}
                {' — '}
                {previewLang === 'vi' ? state.published.bodyVi : state.published.bodyEn}
              </p>
              <button
                className="btn ghost"
                disabled={pendingBusy}
                onClick={() => setPending({ kind: 'unpublish' })}
              >
                {t('social.cms.unpublish')}
              </button>
            </div>
          ) : (
            <p className="state-hint">{t('social.cms.nothingLive')}</p>
          )}

          <div className="social-draft-grid">
            {(['Vi', 'En'] as const).map((suffix) => {
              const titleValue = draftForm[`title${suffix}`] as string;
              const bodyValue = draftForm[`body${suffix}`] as string;
              const titleTooLong = titleValue.trim().length > TITLE_MAX;
              const bodyTooLong = bodyValue.trim().length > BODY_MAX;
              const titleErrorId = `social-title-${suffix}-error`;
              const bodyErrorId = `social-body-${suffix}-error`;
              return (
                <fieldset key={suffix} className="social-draft-lang">
                  <legend>{suffix === 'Vi' ? 'Tiếng Việt' : 'English'}</legend>
                  <label className="field">
                    <span>
                      {t('social.cms.fieldTitle')} — {titleValue.length}/{TITLE_MAX}
                    </span>
                    <input
                      className="input"
                      maxLength={TITLE_MAX + 20}
                      value={titleValue}
                      aria-invalid={titleTooLong || undefined}
                      aria-describedby={titleTooLong ? titleErrorId : undefined}
                      onChange={(e) => setDraftForm({ ...draftForm, [`title${suffix}`]: e.target.value })}
                    />
                    {titleTooLong && (
                      <span id={titleErrorId} className="form-error">
                        {t('social.cms.tooLong', { max: TITLE_MAX })}
                      </span>
                    )}
                  </label>
                  <label className="field">
                    <span>
                      {t('social.cms.fieldBody')} — {bodyValue.length}/{BODY_MAX}
                    </span>
                    <textarea
                      className="input"
                      rows={3}
                      maxLength={BODY_MAX + 50}
                      value={bodyValue}
                      aria-invalid={bodyTooLong || undefined}
                      aria-describedby={bodyTooLong ? bodyErrorId : undefined}
                      onChange={(e) => setDraftForm({ ...draftForm, [`body${suffix}`]: e.target.value })}
                    />
                    {bodyTooLong && (
                      <span id={bodyErrorId} className="form-error">
                        {t('social.cms.tooLong', { max: BODY_MAX })}
                      </span>
                    )}
                  </label>
                </fieldset>
              );
            })}
          </div>

          <div className="social-preview">
            <div className="social-preview-head">
              <h3>{t('social.cms.preview')}</h3>
              <select
                className="select"
                aria-label={t('social.cms.previewLang')}
                value={previewLang}
                onChange={(e) => setPreviewLang(e.target.value as 'vi' | 'en')}
              >
                <option value="vi">VI</option>
                <option value="en">EN</option>
              </select>
            </div>
            <div className="social-preview-card">
              <strong>{previewTitle.trim() || '—'}</strong>
              <p>{previewBody.trim() || '—'}</p>
            </div>
          </div>

          <div className="social-cms-actions">
            <button
              className="btn ghost"
              disabled={!draftValid || draftBusy || pendingBusy}
              onClick={() => void saveDraft()}
            >
              {t('social.cms.saveDraft')}
            </button>
            <button
              className="btn primary"
              disabled={!state.draft || pendingBusy}
              onClick={() => setPending({ kind: 'publish' })}
            >
              {t('social.cms.publish')}
            </button>
          </div>
        </section>
      </div>

      {pending && (
        <ConfirmDialog
          title={
            pending.kind === 'flags'
              ? t('social.confirm.flags')
              : pending.kind === 'publish'
                ? t('social.confirm.publish')
                : t('social.confirm.unpublish')
          }
          confirmDanger={pending.kind === 'unpublish'}
          busy={pendingBusy || flagsBusy}
          onConfirm={() => void runPending()}
          onCancel={() => setPending(null)}
        >
          {pending.kind === 'flags' && (
            <p>
              {t('social.flags.web')}: {pending.webEnabled ? t('common.on') : t('common.off')}
              {' · '}
              {t('social.flags.video')}: {pending.videoUploadsEnabled ? t('common.on') : t('common.off')}
            </p>
          )}
        </ConfirmDialog>
      )}
      <Toast message={toast.message} />
    </>
  );
}
