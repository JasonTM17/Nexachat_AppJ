import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useSession } from '../auth';
import { useI18n } from '../i18n';
import { ApiException } from '../api';
import type { StringKey } from '../i18n';

export default function LoginPage() {
  const { t, lang, setLang } = useI18n();
  const { signIn } = useSession();
  const navigate = useNavigate();
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  // Set by api.ts when a mid-session refresh fails; consumed once here.
  const [sessionExpired] = useState(() => {
    if (sessionStorage.getItem('admin.sessionExpired')) {
      sessionStorage.removeItem('admin.sessionExpired');
      return true;
    }
    return false;
  });

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      await signIn(identifier.trim(), password);
      navigate('/overview', { replace: true });
    } catch (err) {
      let key: StringKey = 'login.error.invalid';
      if (err instanceof ApiException) {
        if (err.status === 403) key = 'login.error.staff';
        else if (err.status !== 401) key = 'login.error.server';
        if (err.code === 'ACCOUNT_SUSPENDED') key = 'login.error.suspended';
      } else {
        // No response at all: network failure, CORS, or server offline.
        key = 'login.error.network';
      }
      setError(t(key));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <div className="card login-card">
        <div className="login-head">
          <h1>{t('app.name')}</h1>
          <select
            className="select"
            aria-label={t('lang.label')}
            value={lang}
            onChange={(e) => setLang(e.target.value as 'en' | 'vi')}
          >
            <option value="en">EN</option>
            <option value="vi">VI</option>
          </select>
        </div>
        <div className="sub">{t('login.title')}</div>
        <form onSubmit={submit} aria-busy={busy}>
          {sessionExpired && (
            <div className="form-error" role="status">
              {t('login.notice.sessionExpired')}
            </div>
          )}
          {error && (
            <div className="form-error" role="alert">
              {error}
            </div>
          )}
          <div className="field">
            <label htmlFor="identifier">{t('login.phone')}</label>
            <input
              id="identifier"
              className="input"
              type="text"
              autoComplete="username"
              value={identifier}
              onChange={(e) => setIdentifier(e.target.value)}
              required
              autoFocus
              disabled={busy}
            />
          </div>
          <div className="field">
            <label htmlFor="password">{t('login.password')}</label>
            <input
              id="password"
              className="input"
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              disabled={busy}
            />
          </div>
          <button
            className="btn primary"
            type="submit"
            disabled={busy}
            style={{ width: '100%' }}
          >
            {busy ? t('common.loading') : t('login.submit')}
          </button>
        </form>
      </div>
    </div>
  );
}
