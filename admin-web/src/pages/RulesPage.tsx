import { useCallback, useEffect, useState } from 'react';
import { api, type AlertRule } from '../api';
import { useI18n } from '../i18n';
import { Chip, ConfirmDialog, Empty, ErrorBlock, Loading } from '../components/ui';
import { useSession } from '../auth';

const SEVERITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'];
const CATEGORIES = [
  'SELF_HARM',
  'THREAT_VIOLENCE',
  'SCAM_PHISHING',
  'CREDENTIAL_SHARING',
  'ID_NUMBER',
  'MINOR_SOLICITATION',
  'DOXXING',
  'CUSTOM',
];
const TYPES = ['KEYWORD', 'REGEX', 'CARD'];

interface TestHit {
  offset: number;
  length: number;
  snippet: string;
}

export default function RulesPage() {
  const { t } = useI18n();
  const { isAdmin } = useSession();
  const [rules, setRules] = useState<AlertRule[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [showNew, setShowNew] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState<AlertRule | null>(null);

  const [form, setForm] = useState({
    name: '',
    category: 'CUSTOM',
    severity: 'MEDIUM',
    patternType: 'KEYWORD',
    pattern: '',
    locale: 'any',
    description: '',
  });
  const [formError, setFormError] = useState('');

  const [testType, setTestType] = useState('KEYWORD');
  const [testPattern, setTestPattern] = useState('');
  const [testSample, setTestSample] = useState('');
  const [testResult, setTestResult] = useState<{ matched: boolean; matches: TestHit[] } | null>(
    null,
  );

  const load = useCallback(() => {
    setFailed(false);
    api<AlertRule[]>('/api/v1/admin/rules')
      .then(setRules)
      .catch(() => setFailed(true));
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const toggle = (rule: AlertRule) => {
    api(`/api/v1/admin/rules/${rule.id}`, {
      method: 'PATCH',
      body: { enabled: !rule.enabled },
    }).then(load);
  };

  const changeSeverity = (rule: AlertRule, severity: string) => {
    api(`/api/v1/admin/rules/${rule.id}`, {
      method: 'PATCH',
      body: { severity },
    }).then(load);
  };

  const create = async () => {
    setFormError('');
    try {
      await api('/api/v1/admin/rules', {
        method: 'POST',
        body: {
          name: form.name,
          category: form.category,
          severity: form.severity,
          patternType: form.patternType,
          pattern: form.patternType === 'CARD' ? undefined : form.pattern,
          locale: form.locale || undefined,
          description: form.description || undefined,
        },
      });
      setShowNew(false);
      setForm({ ...form, name: '', pattern: '', description: '' });
      load();
    } catch {
      setFormError(t('common.error'));
    }
  };

  const remove = () => {
    if (!confirmDelete) return;
    api(`/api/v1/admin/rules/${confirmDelete.id}`, { method: 'DELETE' }).then(() => {
      setConfirmDelete(null);
      load();
    });
  };

  const runTest = () => {
    setTestResult(null);
    api<{ matched: boolean; matches: TestHit[] }>('/api/v1/admin/rules/test', {
      method: 'POST',
      body: {
        patternType: testType,
        pattern: testType === 'CARD' ? undefined : testPattern,
        sampleText: testSample,
      },
    })
      .then(setTestResult)
      .catch(() => setTestResult({ matched: false, matches: [] }));
  };

  if (failed) return <ErrorBlock onRetry={load} />;
  if (!rules) return <Loading />;

  return (
    <>
      {isAdmin && (
        <div className="filters">
          <button className="btn primary" onClick={() => setShowNew((v) => !v)}>
            {t('rules.new')}
          </button>
        </div>
      )}

      {showNew && (
        <div className="card" style={{ marginBottom: 16 }}>
          {formError && <div className="form-error">{formError}</div>}
          <div className="filters">
            <div className="field" style={{ minWidth: 200 }}>
              <label htmlFor="rule-name">{t('rules.field.name')}</label>
              <input
                id="rule-name"
                className="input"
                value={form.name}
                onChange={(e) => setForm({ ...form, name: e.target.value })}
              />
            </div>
            <div className="field">
              <label htmlFor="rule-cat">{t('rules.col.category')}</label>
              <select
                id="rule-cat"
                className="select"
                value={form.category}
                onChange={(e) => setForm({ ...form, category: e.target.value })}
              >
                {CATEGORIES.map((c) => (
                  <option key={c}>{c}</option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="rule-sev">{t('rules.col.severity')}</label>
              <select
                id="rule-sev"
                className="select"
                value={form.severity}
                onChange={(e) => setForm({ ...form, severity: e.target.value })}
              >
                {SEVERITIES.map((s) => (
                  <option key={s}>{s}</option>
                ))}
              </select>
            </div>
            <div className="field">
              <label htmlFor="rule-type">{t('rules.col.type')}</label>
              <select
                id="rule-type"
                className="select"
                value={form.patternType}
                onChange={(e) => setForm({ ...form, patternType: e.target.value })}
              >
                {TYPES.map((tp) => (
                  <option key={tp}>{tp}</option>
                ))}
              </select>
            </div>
          </div>
          {form.patternType !== 'CARD' && (
            <div className="field">
              <label htmlFor="rule-pattern">{t('rules.field.pattern')}</label>
              <input
                id="rule-pattern"
                className="input mono"
                value={form.pattern}
                onChange={(e) => setForm({ ...form, pattern: e.target.value })}
              />
            </div>
          )}
          <div className="dialog-actions">
            <button className="btn ghost" onClick={() => setShowNew(false)}>
              {t('common.cancel')}
            </button>
            <button className="btn primary" onClick={() => void create()} disabled={!form.name}>
              {t('common.save')}
            </button>
          </div>
        </div>
      )}

      {rules.length === 0 ? (
        <Empty />
      ) : (
        <div className="table-wrap" style={{ marginBottom: 20 }}>
          <table>
            <thead>
              <tr>
                <th>{t('rules.col.name')}</th>
                <th>{t('rules.col.category')}</th>
                <th>{t('rules.col.severity')}</th>
                <th>{t('rules.col.type')}</th>
                <th>{t('rules.col.pattern')}</th>
                <th>{t('rules.col.enabled')}</th>
                {isAdmin && <th />}
              </tr>
            </thead>
            <tbody>
              {rules.map((r) => (
                <tr className="plain" key={r.id}>
                  <td>
                    <div>{r.name}</div>
                    {r.builtIn && <Chip tone="low">{t('rules.builtIn')}</Chip>}
                  </td>
                  <td>{r.category}</td>
                  <td>
                    {isAdmin ? (
                      <select
                        className="select"
                        style={{ width: 'auto', padding: '4px 10px' }}
                        aria-label={`${t('rules.col.severity')} ${r.name}`}
                        value={r.severity}
                        onChange={(e) => changeSeverity(r, e.target.value)}
                      >
                        {SEVERITIES.map((s) => (
                          <option key={s}>{s}</option>
                        ))}
                      </select>
                    ) : (
                      <Chip tone={r.severity.toLowerCase()}>{r.severity}</Chip>
                    )}
                  </td>
                  <td>{r.patternType}</td>
                  <td className="mono truncate">{r.pattern ?? '—'}</td>
                  <td>
                    <input
                      type="checkbox"
                      className="checkbox"
                      aria-label={`${t('rules.col.enabled')} ${r.name}`}
                      checked={r.enabled}
                      disabled={!isAdmin}
                      onChange={() => toggle(r)}
                    />
                  </td>
                  {isAdmin && (
                    <td>
                      {!r.builtIn && (
                        <button className="btn danger sm" onClick={() => setConfirmDelete(r)}>
                          {t('common.delete')}
                        </button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="card">
        <h3 style={{ marginTop: 0 }}>{t('rules.test')}</h3>
        <div className="filters">
          <select
            className="select"
            aria-label={t('rules.col.type')}
            value={testType}
            onChange={(e) => setTestType(e.target.value)}
          >
            {TYPES.map((tp) => (
              <option key={tp}>{tp}</option>
            ))}
          </select>
          {testType !== 'CARD' && (
            <input
              className="input mono"
              placeholder={t('rules.field.pattern')}
              aria-label={t('rules.field.pattern')}
              value={testPattern}
              onChange={(e) => setTestPattern(e.target.value)}
            />
          )}
        </div>
        <div className="field">
          <textarea
            className="textarea"
            rows={3}
            placeholder={t('rules.test.sample')}
            aria-label={t('rules.test.sample')}
            value={testSample}
            onChange={(e) => setTestSample(e.target.value)}
          />
        </div>
        <button className="btn" onClick={runTest} disabled={!testSample}>
          {t('rules.test.run')}
        </button>
        {testResult && (
          <div style={{ marginTop: 12 }}>
            <Chip tone={testResult.matched ? 'high' : 'success'}>
              {testResult.matched ? t('rules.test.matched') : t('rules.test.none')}
            </Chip>
            {testResult.matches.map((m, i) => (
              <div className="snippet" key={i}>
                {m.snippet}
                <div className="muted" style={{ fontSize: 11 }}>
                  offset {m.offset} · len {m.length}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {confirmDelete && (
        <ConfirmDialog
          title={`${t('common.delete')} ${confirmDelete.name}`}
          requireReason={false}
          confirmDanger
          onConfirm={() => remove()}
          onCancel={() => setConfirmDelete(null)}
        />
      )}
    </>
  );
}
