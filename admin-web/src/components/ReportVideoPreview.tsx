import { useEffect, useRef, useState } from 'react';
import { api } from '../api';
import { useI18n } from '../i18n';

const MAX_VIDEO_BYTES = 4 * 1024 * 1024;

/**
 * Click-to-load staff preview of the video attached to one reported post.
 * Bytes are fetched only on an explicit click through the audited staff
 * endpoint, validated as a bounded video/mp4 blob, and the object URL is
 * revoked on close or unmount. No autoplay, no automatic retry: every fetch
 * is a deliberate staff action the server audits.
 */
export function ReportVideoPreview({ reportId }: { reportId: string }) {
  const { t } = useI18n();
  const [url, setUrl] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [failed, setFailed] = useState(false);
  const pending = useRef<AbortController | null>(null);
  const objectUrl = useRef<string | null>(null);
  const loadBtnRef = useRef<HTMLButtonElement>(null);
  const restoreFocusRef = useRef(false);

  useEffect(
    () => {
      setUrl(null);
      setLoading(false);
      setFailed(false);
      return () => {
        pending.current?.abort();
        pending.current = null;
        if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
        objectUrl.current = null;
      };
    },
    [reportId],
  );

  useEffect(() => {
    if (!url && restoreFocusRef.current) {
      restoreFocusRef.current = false;
      loadBtnRef.current?.focus();
    }
  }, [url]);

  function close() {
    if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    objectUrl.current = null;
    setUrl(null);
  }

  function handleCloseClick() {
    restoreFocusRef.current = true;
    close();
  }

  async function load() {
    if (pending.current || objectUrl.current) return;
    const controller = new AbortController();
    pending.current = controller;
    setFailed(false);
    setLoading(true);
    try {
      const blob = await api<Blob>(
        `/api/v1/admin/feed/reports/${encodeURIComponent(reportId)}/video`,
        { method: 'POST', asBlob: true, signal: controller.signal },
      );
      if (
        pending.current !== controller ||
        controller.signal.aborted
      ) {
        return;
      }
      const mime = blob.type.split(';')[0].trim().toLowerCase();
      if (mime !== 'video/mp4' || blob.size < 1 || blob.size > MAX_VIDEO_BYTES) {
        setFailed(true);
        return;
      }
      const created = URL.createObjectURL(blob);
      objectUrl.current = created;
      setUrl(created);
    } catch {
      if (pending.current === controller && !controller.signal.aborted) {
        setFailed(true);
      }
    } finally {
      if (pending.current === controller) {
        pending.current = null;
        setLoading(false);
      }
    }
  }

  if (url) {
    return (
      <span className="report-video">
        <video
          controls
          playsInline
          preload="metadata"
          aria-label={t('feed.video.label')}
          src={url}
          onError={() => {
            close();
            setFailed(true);
          }}
        />
        <button type="button" className="btn ghost sm" onClick={handleCloseClick}>
          {t('feed.video.close')}
        </button>
      </span>
    );
  }

  return (
    <span className="report-video">
      <button
        ref={loadBtnRef}
        type="button"
        className="btn sm"
        disabled={loading}
        aria-busy={loading || undefined}
        onClick={() => void load()}
      >
        {t('feed.video.open')}
      </button>
      <span role="status" className={failed ? 'report-video-status error' : 'report-video-status'}>
        {loading ? t('feed.video.loading') : null}
        {failed ? t('feed.video.failed') : null}
      </span>
    </span>
  );
}
