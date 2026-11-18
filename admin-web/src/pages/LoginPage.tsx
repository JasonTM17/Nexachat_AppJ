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
  const [phone, setPhone] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      await signIn(phone.trim(), password);
      navigate('/overview', { replace: true });
    } catch (err) {
      let key: StringKey = 'login.error.invalid';
      if (err instanceof ApiException) {
        if (err.status === 403) key = 'login.error.staff';
        if (err.code === 'ACCOUNT_SUSPENDED') key = 'login.error.suspended';
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
        <form onSubmit={submit}>
          {error && (
            <div className="form-error" role="alert">
              {error}
            </div>
          )}
          <div className="field">
            <label htmlFor="phone">{t('login.phone')}</label>
            <input
              id="phone"
              className="input"
              type="tel"
              autoComplete="username"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              required
              autoFocus
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
            />
          </div>
          <button className="btn primary" type="submit" disabled={busy} style={{ width: '100%' }}>
            {t('login.submit')}
          </button>
        </form>
      </div>
    </div>
  );
}
