import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, type Overview } from '../api';
import { useI18n, type StringKey } from '../i18n';
import { useLatestRequest, useVisiblePolling } from '../lib/async';
import { timeLocale } from '../lib/locale';
import {
  Chip,
  ErrorBlock,
  Loading,
  RefreshControl,
  fmtBytes,
  fmtNum,
} from '../components/ui';

const POLL_MS = 60_000;

interface TrendPoint {
  date: string;
  value: number;
}

// ISO day strings must not shift into the previous local day for users behind
// UTC, so chart date labels are formatted explicitly in UTC.
function fmtChartDay(iso: string): string {
  const d = new Date(`${iso}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return iso;
  return new Intl.DateTimeFormat(timeLocale(), {
    month: 'short',
    day: 'numeric',
    timeZone: 'UTC',
  }).format(d);
}

function Spark({ points, label }: { points: TrendPoint[]; label: string }) {
  const { t } = useI18n();
  if (points.length === 0) return null;
  const w = 560;
  const h = 140;
  const pad = 8;
  // Extra right inset keeps the final point inside the card edge.
  const padR = 36;
  const values = points.map((p) => p.value);
  const max = Math.max(1, ...values);
  const min = Math.min(...values);
  const stepX = points.length > 1 ? (w - pad - padR) / (points.length - 1) : 0;
  const coords = values.map(
    (v, i) => `${pad + i * stepX},${h - pad - (v / max) * (h - pad * 2)}`,
  );
  const area = `${pad},${h - pad} ${coords.join(' ')} ${w - padR},${h - pad}`;
  const start = fmtChartDay(points[0].date);
  const end = fmtChartDay(points[points.length - 1].date);
  const windowLabel = t('chart.window', { start, end });
  const minMaxLabel = t('chart.minMax', { min: fmtNum(min), max: fmtNum(max) });
  return (
    <>
      <div className="chart-window">
        <span style={{ fontWeight: 600 }}>{label}</span>
        <span>{windowLabel}</span>
      </div>
      <svg className="spark" viewBox={`0 0 ${w} ${h}`} role="img" aria-label={label}>
        <title>{`${label}: ${windowLabel}; ${minMaxLabel}`}</title>
        <polygon className="fill" points={area} />
        <polyline points={coords.join(' ')} />
      </svg>
      <div className="chart-axis" aria-hidden="true">
        <span>{start}</span>
        <span className="minmax">{minMaxLabel}</span>
        <span>{end}</span>
      </div>
      <details className="chart-data">
        <summary>{t('chart.data')}</summary>
        <div className="chart-data-scroll">
          <table>
            <thead>
              <tr>
                <th scope="col">{t('chart.date')}</th>
                <th scope="col">{label}</th>
              </tr>
            </thead>
            <tbody>
              {points.map((p) => (
                <tr className="plain" key={p.date}>
                  <td>{fmtChartDay(p.date)}</td>
                  <td>{fmtNum(p.value)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </>
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
    text: delta > 0 ? `+${fmtNum(delta)}` : fmtNum(delta),
    tone: delta > 0 ? 'success' : delta < 0 ? 'critical' : 'low',
  };
}

// Rates format through Intl so the decimal separator follows the display
// locale instead of a hardcoded en-US percent string.
function fmtPercent(rate: number): string {
  return new Intl.NumberFormat(timeLocale(), {
    style: 'percent',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(rate);
}

export default function OverviewPage() {
  const { t } = useI18n();
  const [data, setData] = useState<Overview | null>(null);
  const [failed, setFailed] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [stale, setStale] = useState(false);
  const [updatedAt, setUpdatedAt] = useState<Date | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  // load() is stable so it cannot close over `data`; this ref mirrors it so the
  // failure branch can tell an initial-load failure from a failed refresh.
  const hasData = useRef(false);
  // The poll, the visibility handler and the manual button can overlap; only
  // the newest response may write state.
  const latest = useLatestRequest();

  const load = useCallback(() => {
    setRefreshing(true);
    latest(api<Overview>('/api/v1/admin/overview'), (d, err) => {
      setRefreshing(false);
      if (!d) {
        // A failed poll must not wipe the last good data: keep it and flag the
        // header as stale instead of swapping in the error block.
        setError(err);
        if (hasData.current) setStale(true);
        else setFailed(true);
        return;
      }
      hasData.current = true;
      setData(d);
      setUpdatedAt(new Date());
      setError(null);
      setFailed(false);
      setStale(false);
    });
  }, [latest]);

  useEffect(() => {
    load();
  }, [load]);
  useVisiblePolling(load, POLL_MS);

  if (failed) return <ErrorBlock onRetry={load} error={error} />;
  if (!data) return <Loading />;

  const k = data.kpis;
  const regValues = data.series.map((p) => p.registrations);
  const msgValues = data.series.map((p) => p.messages);
  const regSeries = data.series.map((p) => ({ date: p.date, value: p.registrations }));
  const msgSeries = data.series.map((p) => ({ date: p.date, value: p.messages }));
  const tiles: {
    key: StringKey;
    value: string;
    sub?: string;
    delta?: { text: string; tone: string };
    link?: string;
  }[] = [
    {
      key: 'kpi.registrationsToday',
      value: fmtNum(k.registrationsToday),
      delta: deltaVsYesterday(k.registrationsToday, regValues) ?? undefined,
    },
    { key: 'kpi.dau', value: fmtNum(k.dau) },
    { key: 'kpi.wau', value: fmtNum(k.wau) },
    {
      key: 'kpi.messagesToday',
      value: fmtNum(k.messagesToday),
      delta: deltaVsYesterday(k.messagesToday, msgValues) ?? undefined,
    },
    { key: 'kpi.activeCalls', value: fmtNum(k.activeCalls) },
    {
      key: 'kpi.pendingAlerts',
      value: fmtNum(k.pendingAlerts),
      sub: k.queue.dryRun ? t('kpi.queue.dryRun') : undefined,
      // The most common moderator jump deep-links straight to the queue.
      link: '/alerts',
    },
    { key: 'kpi.databaseBytes', value: fmtBytes(k.databaseBytes) },
    { key: 'kpi.uploadsBytes', value: fmtBytes(k.uploadsBytes) },
    { key: 'kpi.errorRate', value: fmtPercent(k.errorRate) },
    {
      key: 'kpi.queue',
      value: fmtNum(k.queue.depth),
      sub: t('kpi.queue.detail', {
        dropped: k.queue.dropped,
        scanned: k.queue.scanned,
      }),
    },
  ];

  return (
    <>
      <RefreshControl
        updatedAt={updatedAt}
        onRefresh={load}
        refreshing={refreshing}
        stale={stale}
      />
      <div className="kpi-grid">
        {tiles.map((tile) => {
          const body = (
            <>
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
            </>
          );
          // Linked tiles keep the KPI look; only the wrapper changes.
          return tile.link ? (
            <Link key={tile.key} to={tile.link} className="card kpi kpi-link">
              {body}
            </Link>
          ) : (
            <div className="card kpi" key={tile.key}>
              {body}
            </div>
          );
        })}
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
