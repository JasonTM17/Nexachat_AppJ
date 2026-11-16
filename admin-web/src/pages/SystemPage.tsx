import { useCallback, useEffect, useState } from 'react';
import { api, type SystemHealth } from '../api';
import { useI18n } from '../i18n';
import { Chip, ErrorBlock, Loading, fmtTime } from '../components/ui';

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

  if (failed) return <ErrorBlock onRetry={load} />;
  if (!health) return <Loading />;

  return (
    <>
      <div className="kpi-grid">
        <div className="card kpi">
          <div className="label">{t('system.status')}</div>
          <div className="value">
            <Chip tone={health.status === 'UP' ? 'success' : 'critical'}>
              {health.status === 'UP' ? t('system.up') : t('system.down')}
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
              <Chip tone={svc.status === 'UP' ? 'success' : 'critical'}>
                {svc.status === 'UP' ? t('system.up') : t('system.down')}
              </Chip>
            </div>
          ))}
        </div>
      </div>
    </>
  );
}
