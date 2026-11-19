import { useCallback, useEffect, useRef, useState } from 'react';
import { api, type Overview } from '../api';
import { useI18n, type StringKey } from '../i18n';
import { Chip, ErrorBlock, Loading, fmtBytes } from '../components/ui';

const POLL_MS = 60_000;

function Spark({ points, label }: { points: number[]; label: string }) {
  const w = 560;
  const h = 140;
  const pad = 8;
  // Extra right inset keeps the end value + label inside the card edge.
  const padR = 36;
  const max = Math.max(1, ...points);
  const stepX = points.length > 1 ? (w - pad - padR) / (points.length - 1) : 0;
  const coords = points.map(
    (v, i) => `${pad + i * stepX},${h - pad - (v / max) * (h - pad * 2)}`,
  );
  const area = `${pad},${h - pad} ${coords.join(' ')} ${w - padR},${h - pad}`;
  return (
    <svg className="spark" viewBox={`0 0 ${w} ${h}`} role="img" aria-label={label}>
      <polygon className="fill" points={area} />
      <polyline points={coords.join(' ')} />
      <text x={pad} y={14}>{label}</text>
      <text x={w - 8} y={14} textAnchor="end">{max}</text>
    </svg>
  );
}

// Yesterday is the second-to-last point of the daily series (the last point is
// today); a series shorter than two points cannot support a delta.
function deltaVsYesterday(
  today: number,
  series: number[],
): { text: string; tone: string } | null {
  if (series.length < 2) return null;
  const delta = today - series[series.length - 2];
  return {
    text: delta > 0 ? `+${delta}` : String(delta),
    tone: delta > 0 ? 'success' : delta < 0 ? 'critical' : 'low',
  };
}

export default function OverviewPage() {
  const { t } = useI18n();
  const [data, setData] = useState<Overview | null>(null);
  const [failed, setFailed] = useState(false);
  const [stale, setStale] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  // load() is stable so it cannot close over `data`; this ref mirrors it so the
  // catch can tell an initial-load failure from a failed refresh.
  const hasData = useRef(false);

  const load = useCallback(() => {
    api<Overview>('/api/v1/admin/overview')
      .then((d) => {
        hasData.current = true;
        setData(d);
        setUpdatedAt(new Date());
        setFailed(false);
        setStale(false);
      })
      .catch(() => {
        // A failed poll must not wipe the last good data: keep it and flag the
        // header as stale instead of swapping in the error block.
        if (hasData.current) setStale(true);
        else setFailed(true);
      });
  }, []);

  useEffect(() => {
    load();
    // Fixed cadence, but ticks are skipped while the tab is hidden; returning
    // to the tab refreshes immediately via the visibilitychange handler.
    const id = setInterval(() => {
      if (document.visibilityState === 'visible') load();
    }, POLL_MS);
    const onVisibility = () => {
      if (document.visibilityState === 'visible') load();
    };
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      clearInterval(id);
      document.removeEventListener('visibilitychange', onVisibility);
    };
  }, [load]);

  if (failed) return <ErrorBlock onRetry={load} />;
  if (!data) return <Loading />;

  const k = data.kpis;
  const regSeries = data.series.map((p) => p.registrations);
  const msgSeries = data.series.map((p) => p.messages);
  const tiles: {
    key: StringKey;
    value: string;
    sub?: string;
    delta?: { text: string; tone: string };
  }[] = [
    {
      key: 'kpi.registrationsToday',
      value: String(k.registrationsToday),
      delta: deltaVsYesterday(k.registrationsToday, regSeries) ?? undefined,
    },
    { key: 'kpi.dau', value: String(k.dau) },
    { key: 'kpi.wau', value: String(k.wau) },
    {
      key: 'kpi.messagesToday',
      value: String(k.messagesToday),
      delta: deltaVsYesterday(k.messagesToday, msgSeries) ?? undefined,
    },
    { key: 'kpi.activeCalls', value: String(k.activeCalls) },
    {
      key: 'kpi.pendingAlerts',
      value: String(k.pendingAlerts),
      sub: k.queue.dryRun ? t('kpi.queue.dryRun') : undefined,
    },
    { key: 'kpi.databaseBytes', value: fmtBytes(k.databaseBytes) },
    { key: 'kpi.uploadsBytes', value: fmtBytes(k.uploadsBytes) },
    { key: 'kpi.errorRate', value: `${(k.errorRate * 100).toFixed(2)}%` },
    {
      key: 'kpi.queue',
      value: String(k.queue.depth),
      sub: t('kpi.queue.detail', {
        dropped: k.queue.dropped,
        scanned: k.queue.scanned,
      }),
    },
  ];

  return (
    <>
      <div className="page-head">
        <div className="page-head-meta">
          {updatedAt && (
            <span>
              {t('overview.updatedAt', {
                time: updatedAt.toLocaleTimeString(undefined, {
                  hour: '2-digit',
                  minute: '2-digit',
                }),
              })}
            </span>
          )}
          {stale && <Chip tone="high">{t('overview.stale')}</Chip>}
        </div>
        <button className="btn ghost sm" onClick={load}>
          {t('overview.refresh')}
        </button>
      </div>
      <div className="kpi-grid">
        {tiles.map((tile) => (
          <div className="card kpi" key={tile.key}>
            <div className="label">
              {t(tile.key)}{' '}
              {tile.key === 'kpi.queue' && !k.queue.enabled && (
                <Chip tone="medium">{t('kpi.queue.off')}</Chip>
              )}
            </div>
            <div className="value">
              {tile.value}
              {tile.delta && (
                <Chip tone={tile.delta.tone} label={t('overview.delta.yesterday')}>
                  {tile.delta.text}
                </Chip>
              )}
            </div>
            {tile.sub && <div className="sub">{tile.sub}</div>}
          </div>
        ))}
      </div>
      <div className="chart-row">
        <div className="card">
          <Spark label={t('chart.registrations')} points={regSeries} />
        </div>
        <div className="card">
          <Spark label={t('chart.messages')} points={msgSeries} />
        </div>
      </div>
    </>
  );
}
