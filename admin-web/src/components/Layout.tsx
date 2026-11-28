import { useEffect } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useSession } from '../auth';
import { useI18n, type StringKey } from '../i18n';
import { tokenStore } from '../api';
import { NavIcon, type NavIconName } from './NavIcon';
import { ThemeToggle } from './ThemeToggle';

const NAV: { to: string; key: StringKey; icon: NavIconName }[] = [
  { to: '/overview', key: 'nav.overview', icon: 'overview' },
  { to: '/users', key: 'nav.users', icon: 'users' },
  { to: '/alerts', key: 'nav.alerts', icon: 'alerts' },
  { to: '/feed', key: 'nav.feed', icon: 'feed' },
  { to: '/social', key: 'nav.social', icon: 'social' },
  { to: '/rules', key: 'nav.rules', icon: 'rules' },
  { to: '/audit', key: 'nav.audit', icon: 'audit' },
  { to: '/system', key: 'nav.system', icon: 'system' },
];

const TITLES: Record<string, StringKey> = {
  '/overview': 'nav.overview',
  '/users': 'nav.users',
  '/alerts': 'nav.alerts',
  '/feed': 'nav.feed',
  '/social': 'nav.social',
  '/rules': 'nav.rules',
  '/audit': 'nav.audit',
  '/system': 'nav.system',
};

const BLURBS: Record<string, StringKey> = {
  '/overview': 'page.overview',
  '/users': 'page.users',
  '/alerts': 'page.alerts',
  '/feed': 'page.feed',
  '/social': 'page.social',
  '/rules': 'page.rules',
  '/audit': 'page.audit',
  '/system': 'page.system',
};

export default function Layout() {
  const { t, lang, setLang } = useI18n();
  const { signOut, me } = useSession();
  const navigate = useNavigate();
  const location = useLocation();

  const title = TITLES[location.pathname] ?? 'app.name';
  const blurb = BLURBS[location.pathname];

  // Per-route window title so tabs and screen readers identify the page.
  useEffect(() => {
    document.title = `${t(title)} · ${t('app.name')}`;
  }, [title, t]);

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        {t('nav.skip')}
      </a>
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
              <NavIcon name={item.icon} />
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
          <NavIcon name="logout" />
          <span className="label">{t('nav.logout')}</span>
        </button>
      </aside>
      <main className="main" id="main">
        <header className="topbar">
          <div className="topbar-title">
            <h1>{t(title)}</h1>
            {blurb && <p className="page-blurb">{t(blurb)}</p>}
          </div>
          <div className="topbar-actions">
            {me && (
              <span className="who">
                {me.displayName && <span className="who-name">{me.displayName}</span>}
                <span className="chip">{me.role}</span>
              </span>
            )}
            <select
              className="select lang-select"
              aria-label={t('lang.label')}
              value={lang}
              onChange={(e) => setLang(e.target.value as 'en' | 'vi')}
            >
              <option value="en">EN</option>
              <option value="vi">VI</option>
            </select>
            <ThemeToggle />
          </div>
        </header>
        {!tokenStore.access ? null : <Outlet />}
      </main>
    </div>
  );
}
