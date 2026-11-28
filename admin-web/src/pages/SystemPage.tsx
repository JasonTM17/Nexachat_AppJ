import { useCallback, useEffect, useRef, useState } from 'react';
import { api, type RagStatsSnapshot, type SystemHealth } from '../api';
import { useI18n, type StringKey } from '../i18n';
import { useLatestRequest, useVisiblePolling } from '../lib/async';
import { timeLocale } from '../lib/locale';
import {
  Chip,
  ErrorBlock,
  Loading,
  fmtClock,
  fmtTime,
  useApiErrorMessage,
} from '../components/ui';

// Poll cadence: healthy checks every 30s; consecutive failures back off to
// 60s then 120s so a struggling server is not hammered.
const BACKOFF_MS = [30_000, 60_000, 120_000];
const RAG_POLL_MS = 60_000;

// Map a service status string to a chip tone + localized label. Anything that
// is not a known-healthy value must not render as healthy: unknown statuses
// fall through to the critical 'unknown' state.
export function serviceStatus(status: string): { tone: string; key: StringKey } {
  const s = status.trim().toUpperCase();
  if (s === 'UP' || s === 'OK' || s === 'HEALTHY') {
    return { tone: 'success', key: 'system.up' };
  }
  if (s === 'DEGRADED' || s === 'WARN' || s === 'WARNING') {
    return { tone: 'high', key: 'system.degraded' };
  }
  if (s === 'DOWN' || s === 'OUTAGE' || s === 'ERROR') {
    return { tone: 'critical', key: 'system.down' };
  }
  return { tone: 'critical', key: 'system.unknown' };
}

function fmtUptime(seconds: number): string {
  const d = Math.floor(seconds / 86400);
  const h = Math.floor((seconds % 86400) / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

function fmtMetric(value: number): string {
  return new Intl.NumberFormat(timeLocale(), { maximumFractionDigits: 1 }).format(value);
}

function RagStatsSection() {
  const { t } = useI18n();
  const formatError = useApiErrorMessage();
  const [stats, setStats] = useState<RagStatsSnapshot | null>(null);
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [stale, setStale] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const hasData = useRef(false);
  const latest = useLatestRequest();

  const load = useCallback(() => {
    setRefreshing(true);
    latest(api<RagStatsSnapshot>('/api/v1/admin/rag/stats'), (snapshot, err) => {
      setRefreshing(false);
      if (!snapshot) {
        setError(err);
        if (hasData.current) setStale(true);
        else setFailed(true);
        return;
      }
      hasData.current = true;
      setStats(snapshot);
      setUpdatedAt(new Date());
      setError(null);
      setFailed(false);
      setStale(false);
    });
  }, [latest]);

  useEffect(() => {
    load();
  }, [load]);
  useVisiblePolling(load, RAG_POLL_MS);

  const groups: {
    title: StringKey;
    rows: { label: StringKey; value: number; tone?: string }[];
  }[] | null = stats
    ? [
        {
          title: 'rag.group.traffic',
          rows: [
            { label: 'rag.requests', value: stats.requests },
            { label: 'rag.extractive', value: stats.extractive },
            { label: 'rag.notFound', value: stats.notFound },
          ],
        },
        {
          title: 'rag.group.cache',
          rows: [
            { label: 'rag.cacheHits', value: stats.cacheHits },
            { label: 'rag.llmFallbackCalls', value: stats.llmFallbackCalls },
          ],
        },
        {
          title: 'rag.group.llm',
          rows: [
            { label: 'rag.llmCalls', value: stats.llmCalls },
            { label: 'rag.llmFailures', value: stats.llmFailures },
            {
              label: 'rag.llmBulkheadRejections',
              value: stats.llmBulkheadRejections,
              tone: stats.llmBulkheadRejections > 0 ? 'critical' : undefined,
            },
            { label: 'rag.inputChars', value: stats.inputChars },
            { label: 'rag.meanInputChars', value: stats.meanInputChars },
          ],
        },
        {
          title: 'rag.group.embedding',
          rows: [
            { label: 'rag.embeddingCalls', value: stats.embeddingCalls },
            { label: 'rag.embeddingFailures', value: stats.embeddingFailures },
            { label: 'rag.indexedMessages', value: stats.indexedMessages },
          ],
        },
      ]
    : null;

  return (
    <div className="card rag-panel" aria-labelledby="rag-panel-title">
      <div className="rag-panel-head">
        <div>
          <h3 id="rag-panel-title">{t('rag.title')}</h3>
          <div className="sub muted">{t('rag.sinceRestart')}</div>
          {updatedAt && (
            <div className="muted" style={{ fontSize: 12 }}>
              {t('common.updatedAt', { time: fmtClock(updatedAt) })}
            </div>
          )}
        </div>
        <button className="btn ghost sm" onClick={load} disabled={refreshing}>
          {t('common.refresh')}
        </button>
      </div>
      {failed && !stats ? (
        <ErrorBlock error={error} onRetry={load} />
      ) : !stats || !groups ? (
        <div className="muted" role="status">{t('common.loading')}</div>
      ) : (
        <>
          {stale && (
            <div className="form-error" role="alert">
              {formatError(error)}
            </div>
          )}
          <div className="rag-grid">
            {groups.map((group) => (
              <section key={group.title}>
                <h4>{t(group.title)}</h4>
                <dl>
                  {group.rows.map((row) => (
                    <div className="rag-stat" key={row.label}>
                      <dt>{t(row.label)}</dt>
                      <dd>
                        {row.tone ? (
                          <Chip tone={row.tone}>{fmtMetric(row.value)}</Chip>
                        ) : (
                          fmtMetric(row.value)
                        )}
                      </dd>
                    </div>
                  ))}
                </dl>
              </section>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

export default function SystemPage() {
  const { t } = useI18n();
  const formatError = useApiErrorMessage();
  const [health, setHealth] = useState<SystemHealth | null>(null);
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState<unknown>(null);
  // Consecutive poll failures drive the retry cadence (BACKOFF_MS).
  const failuresRef = useRef(0);
  const seqRef = useRef(0);

  const load = useCallback(() => {
    const seq = ++seqRef.current;
    setFailed(false);
    return api<SystemHealth>('/api/v1/admin/system/health')
      .then((h) => {
        if (seq !== seqRef.current) return;
        failuresRef.current = 0;
        setHealth(h);
        setError(null);
      })
      .catch((err) => {
        if (seq !== seqRef.current) return;
        failuresRef.current += 1;
        setError(err);
        setFailed(true);
      });
  }, []);

  useEffect(() => {
    void load();
    // Ticks skip the request while the tab is hidden; returning to the tab
    // refreshes immediately via the visibilitychange handler. Each completed
    // poll schedules the next at the delay implied by the failure count.
    let timer: ReturnType<typeof setTimeout> | undefined;
    let cancelled = false;
    const schedule = () => {
      const delay = BACKOFF_MS[Math.min(failuresRef.current, BACKOFF_MS.length - 1)];
      timer = setTimeout(tick, delay);
    };
    const tick = () => {
      if (document.visibilityState !== 'visible') {
        schedule();
        return;
      }
      void load().then(() => {
        if (!cancelled) schedule();
      });
    };
    timer = setTimeout(tick, BACKOFF_MS[0]);
    const onVisibility = () => {
      if (document.visibilityState === 'visible') void load();
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      cancelled = true;
      seqRef.current += 1;
      clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [load]);

  // A transient poll failure must not blank a monitoring dashboard that
  // already holds data — only the never-loaded state gets the full block.
  if (failed && !health) return <ErrorBlock error={error} onRetry={load} />;
  if (!health) return <Loading />;

  return (
    <>
      {failed && (
        <div className="form-error" role="alert">
          {formatError(error)} {t('system.stale', { time: fmtTime(health.checkedAt) })}{' '}
          <button className="btn ghost sm" onClick={load}>
            {t('common.retry')}
          </button>
        </div>
      )}
      <div className="kpi-grid">
        <div className="card kpi">
          <div className="label">{t('system.status')}</div>
          <div className="value">
            <Chip tone={serviceStatus(health.status).tone}>
              {t(serviceStatus(health.status).key)}
            </Chip>
          </div>
          <div className="sub">{fmtTime(health.checkedAt)}</div>
        </div>
        <div className="card kpi">
          <div className="label">{t('system.uptime')}</div>
          <div className="value">{fmtUptime(health.uptimeSeconds)}</div>
        </div>
      </div>
      <RagStatsSection />
      <div className="card">
        <h3 style={{ marginTop: 0 }}>{t('system.services')}</h3>
        <div className="status-grid">
          {health.services.map((svc) => (
            <div className="card status-item" key={svc.name}>
              <div>
                <div style={{ fontWeight: 600 }}>{svc.name}</div>
                {svc.detail && <div className="muted" style={{ fontSize: 12 }}>{svc.detail}</div>}
              </div>
              <Chip tone={serviceStatus(svc.status).tone}>
                {t(serviceStatus(svc.status).key)}
              </Chip>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}
