import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useSession } from '../auth';
import { useI18n, type StringKey } from '../i18n';
import { tokenStore } from '../api';
import { initialTheme, persistTheme } from '../lib/theme';

const NAV: { to: string; key: StringKey; icon: string }[] = [
  { to: '/overview', key: 'nav.overview', icon: '◐' },
  { to: '/users', key: 'nav.users', icon: '◍' },
  { to: '/alerts', key: 'nav.alerts', icon: '▲' },
  { to: '/feed', key: 'nav.feed', icon: '❖' },
  { to: '/rules', key: 'nav.rules', icon: '☰' },
  { to: '/audit', key: 'nav.audit', icon: '◷' },
  { to: '/system', key: 'nav.system', icon: '◈' },
];

const TITLES: Record<string, StringKey> = {
  '/overview': 'nav.overview',
  '/users': 'nav.users',
  '/alerts': 'nav.alerts',
  '/feed': 'nav.feed',
  '/rules': 'nav.rules',
  '/audit': 'nav.audit',
  '/system': 'nav.system',
};

export default function Layout() {
  const { t, lang, setLang } = useI18n();
  const { signOut, me } = useSession();
  const navigate = useNavigate();
  const location = useLocation();
  // Tri-state: stored explicit choice wins, otherwise the OS preference.
  // Mounting must NOT persist — only an explicit toggle writes localStorage.
  const [theme, setTheme] = useState<'light' | 'dark'>(initialTheme);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  const toggleTheme = () => {
    const next = theme === 'dark' ? 'light' : 'dark';
    persistTheme(next);
    setTheme(next);
  };

  const title = TITLES[location.pathname] ?? 'app.name';

  // Per-route window title so tabs and screen readers identify the page.
  useEffect(() => {
    document.title = `${t(title)} · ${t('app.name')}`;
  }, [title, t]);

  return (
    <div className="shell">
      <aside className="sidebar">
        <div className="brand">{t('app.name')}</div>
        <nav aria-label={t('nav.primary')}>
          {NAV.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              title={t(item.key)}
              aria-label={t(item.key)}
              className={({ isActive }) => `navlink${isActive ? ' active' : ''}`}
            >
              <span aria-hidden="true">{item.icon}</span>
              <span className="label">{t(item.key)}</span>
            </NavLink>
          ))}
        </nav>
        <button
          className="navlink"
          title={t('nav.logout')}
          aria-label={t('nav.logout')}
          onClick={() => {
            signOut();
            navigate('/login');
          }}
        >
          <span aria-hidden="true">↩</span>
          <span className="label">{t('nav.logout')}</span>
        </button>
      </aside>
      <main className="main">
        <header className="topbar">
          <h1>{t(title)}</h1>
          <div className="topbar-actions">
            {me && <span className="chip">{me.role}</span>}
            <select
              className="select"
              style={{ width: 'auto' }}
              aria-label={t('lang.label')}
              value={lang}
              onChange={(e) => setLang(e.target.value as 'en' | 'vi')}
            >
              <option value="en">EN</option>
              <option value="vi">VI</option>
            </select>
            <button
              className="btn ghost sm"
              onClick={toggleTheme}
              aria-label={t('theme.toggle')}
              aria-pressed={theme === 'dark'}
              title={`${t('theme.light')}/${t('theme.dark')}`}
            >
              {theme === 'dark' ? '☀' : '☾'}
            </button>
          </div>
        </header>
        {!tokenStore.access ? null : <Outlet />}
      </main>
    </div>
  );
}
