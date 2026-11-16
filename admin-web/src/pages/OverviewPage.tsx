import { useCallback, useEffect, useState } from 'react';
import { api, type Overview } from '../api';
import { useI18n, type StringKey } from '../i18n';
import { Chip, ErrorBlock, Loading, fmtBytes } from '../components/ui';

function Spark({ points, label }: { points: number[]; label: string }) {
  const w = 560;
  const h = 140;
  const pad = 8;
  const max = Math.max(1, ...points);
  const stepX = points.length > 1 ? (w - pad * 2) / (points.length - 1) : 0;
  const coords = points.map(
    (v, i) => `${pad + i * stepX},${h - pad - (v / max) * (h - pad * 2)}`,
  );
  const area = `${pad},${h - pad} ${coords.join(' ')} ${w - pad},${h - pad}`;
  return (
    <svg className="spark" viewBox={`0 0 ${w} ${h}`} role="img" aria-label={label}>
      <polygon className="fill" points={area} />
      <polyline points={coords.join(' ')} />
      <text x={pad} y={14}>{label}</text>
      <text x={w - pad} y={14} textAnchor="end">{max}</text>
    </svg>
  );
}

export default function OverviewPage() {
  const { t } = useI18n();
  const [data, setData] = useState<Overview | null>(null);
  const [failed, setFailed] = useState(false);

  const load = useCallback(() => {
    setFailed(false);
    api<Overview>('/api/v1/admin/overview')
      .then(setData)
      .catch(() => setFailed(true));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  if (failed) return <ErrorBlock onRetry={load} />;
  if (!data) return <Loading />;

  const k = data.kpis;
  const tiles: { key: StringKey; value: string; sub?: string }[] = [
    { key: 'kpi.registrationsToday', value: String(k.registrationsToday) },
    { key: 'kpi.dau', value: String(k.dau) },
    { key: 'kpi.wau', value: String(k.wau) },
    { key: 'kpi.messagesToday', value: String(k.messagesToday) },
    { key: 'kpi.activeCalls', value: String(k.activeCalls) },
    {
      key: 'kpi.pendingAlerts',
      value: String(k.pendingAlerts),
      sub: k.queue.dryRun ? 'dry-run' : undefined,
    },
    { key: 'kpi.databaseBytes', value: fmtBytes(k.databaseBytes) },
    { key: 'kpi.uploadsBytes', value: fmtBytes(k.uploadsBytes) },
    { key: 'kpi.errorRate', value: `${(k.errorRate * 100).toFixed(2)}%` },
    {
      key: 'kpi.queue',
      value: String(k.queue.depth),
      sub: `${k.queue.dropped} dropped / ${k.queue.scanned} scanned`,
    },
  ];

  return (
    <>
      <div className="kpi-grid">
        {tiles.map((tile) => (
          <div className="card kpi" key={tile.key}>
            <div className="label">
              {t(tile.key)}{' '}
              {tile.key === 'kpi.queue' && !k.queue.enabled && (
                <Chip tone="medium">off</Chip>
              )}
            </div>
            <div className="value">{tile.value}</div>
            {tile.sub && <div className="sub">{tile.sub}</div>}
          </div>
        ))}
      </div>
      <div className="chart-row">
        <div className="card">
          <Spark
            label={t('chart.registrations')}
            points={data.series.map((p) => p.registrations)}
          />
        </div>
        <div className="card">
          <Spark
            label={t('chart.messages')}
            points={data.series.map((p) => p.messages)}
          />
        </div>
      </div>
    </>
  );
}
