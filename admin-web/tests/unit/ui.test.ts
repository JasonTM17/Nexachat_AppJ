import { describe, expect, it } from 'vitest';
import { truncateJson } from '../../src/components/ui';

describe('truncateJson', () => {
  it('returns values at or under the limit unchanged', () => {
    expect(truncateJson('{"a":1}', 80)).toBe('{"a":1}');
    expect(truncateJson('{"a":1}', 7)).toBe('{"a":1}');
  });

  it('cuts at a token boundary instead of mid-value', () => {
    const detail = JSON.stringify({ field: 'severity', from: 'HIGH', to: 'CRITICAL' });
    const out = truncateJson(detail, 36);
    expect(out).toBe('{"field":"severity","from":"HIGH" …');
  });

  it('falls back to a hard cut when no boundary is near enough', () => {
    const detail = `{"${'k'.repeat(60)}":1}`;
    const out = truncateJson(detail, 36);
    expect(out.endsWith(' …')).toBe(true);
    expect(out.length).toBe(38);
  });
});
