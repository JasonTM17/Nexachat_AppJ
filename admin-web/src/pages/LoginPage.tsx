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
      <div className="login-shell">
        <aside className="login-brand-panel" aria-label={t('app.name')}>
          <div className="login-brand-lockup">
            <svg
              className="login-brand-mark"
              viewBox="0 0 32 32"
              aria-hidden="true"
              focusable="false"
            >
              <rect width="32" height="32" rx="8" fill="#733F68" />
              <g transform="translate(6.08 6.08) scale(0.184)">
                <path
                  fill="#FFFCFF"
                  d="M24 20H36L64 60V20H80V84H66L40 47V72L24 84Z"
                />
              </g>
            </svg>
            <span className="login-brand-name">{t('app.name')}</span>
          </div>
          <div className="login-brand-copy">
            <p className="login-eyebrow">{t('login.eyebrow')}</p>
            <p className="login-headline">{t('login.headline')}</p>
          </div>
          <p className="login-brand-footer">{t('login.restricted')}</p>
        </aside>
        <main className="login-form-panel">
          <div className="login-form">
            <div className="login-form-header">
              <h1>{t('login.title')}</h1>
              <select
                className="select lang-select"
                aria-label={t('lang.label')}
                value={lang}
                onChange={(e) => setLang(e.target.value as 'en' | 'vi')}
              >
                <option value="en">EN</option>
                <option value="vi">VI</option>
              </select>
            </div>
            <p className="login-hint">{t('login.hint')}</p>
            <form onSubmit={submit} aria-busy={busy}>
              {sessionExpired && (
                <div className="form-notice" role="status">
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
                  enterKeyHint="next"
                  value={identifier}
                  onChange={(e) => setIdentifier(e.target.value)}
                  required
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
                  enterKeyHint="go"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                  disabled={busy}
                />
              </div>
              <button className="btn primary login-submit" type="submit" disabled={busy}>
                {busy ? t('common.loading') : t('login.submit')}
              </button>
            </form>
          </div>
        </main>
      </div>
    </div>
  );
}
