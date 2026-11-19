import { useCallback, useEffect, useState } from 'react';
import { api, type SystemHealth } from '../api';
import { useI18n, type StringKey } from '../i18n';
import { Chip, ErrorBlock, Loading, fmtTime } from '../components/ui';

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

export default function SystemPage() {
  const { t } = useI18n();
  const [health, setHealth] = useState<SystemHealth | null>(null);
  const [failed, setFailed] = useState(false);

  const load = useCallback(() => {
    setFailed(false);
    api<SystemHealth>('/api/v1/admin/system/health')
      .then(setHealth)
      .catch(() => setFailed(true));
  }, []);

  useEffect(() => {
    load();
    const id = setInterval(load, 30_000);
    return () => clearInterval(id);
  }, [load]);

  // A transient poll failure must not blank a monitoring dashboard that
  // already holds data — only the never-loaded state gets the full block.
  if (failed && !health) return <ErrorBlock onRetry={load} />;
  if (!health) return <Loading />;

  return (
    <>
      {failed && (
        <div className="form-error" role="alert">
          {t('system.stale', { time: fmtTime(health.checkedAt) })}{' '}
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
