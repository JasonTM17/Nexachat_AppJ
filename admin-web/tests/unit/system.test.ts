import { describe, expect, it } from 'vitest';
import { serviceStatus } from '../../src/pages/SystemPage';

describe('serviceStatus', () => {
  it('maps healthy statuses to success/UP', () => {
    for (const s of ['UP', 'OK', 'HEALTHY', 'up']) {
      expect(serviceStatus(s)).toEqual({ tone: 'success', key: 'system.up' });
    }
  });

  it('maps degraded statuses to warn/Degraded', () => {
    for (const s of ['DEGRADED', 'WARN', 'WARNING', 'degraded']) {
      expect(serviceStatus(s)).toEqual({ tone: 'high', key: 'system.degraded' });
    }
  });

  it('maps down statuses to critical/DOWN', () => {
    for (const s of ['DOWN', 'OUTAGE', 'ERROR']) {
      expect(serviceStatus(s)).toEqual({ tone: 'critical', key: 'system.down' });
    }
  });

  it('never renders unknown values as healthy', () => {
    for (const s of ['', 'STARTING', 'FLAPPY', '???']) {
      const r = serviceStatus(s);
      expect(r.tone).toBe('critical');
      expect(r.key).toBe('system.unknown');
    }
  });
});
