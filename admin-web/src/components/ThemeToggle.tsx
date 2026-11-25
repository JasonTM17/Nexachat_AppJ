import { useEffect, useState } from 'react';
import { useI18n } from '../i18n';
import { initialTheme, persistTheme } from '../lib/theme';

export function ThemeToggle() {
  const { t } = useI18n();
  const [theme, setTheme] = useState<'light' | 'dark'>(initialTheme);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
  }, [theme]);

  const toggle = () => {
    const next = theme === 'dark' ? 'light' : 'dark';
    persistTheme(next);
    setTheme(next);
  };

  return (
    <button
      className="btn ghost sm icon-btn"
      type="button"
      onClick={toggle}
      aria-label={t('theme.toggle')}
      aria-pressed={theme === 'dark'}
      title={`${t('theme.light')}/${t('theme.dark')}`}
    >
      <ThemeIcon dark={theme === 'dark'} />
    </button>
  );
}

function ThemeIcon({ dark }: { dark: boolean }) {
  if (dark) {
    return (
      <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
        <circle cx="12" cy="12" r="4" fill="currentColor" />
        <g stroke="currentColor" strokeWidth="2" strokeLinecap="round">
          <path d="M12 2v2.2M12 19.8V22M2 12h2.2M19.8 12H22M4.9 4.9l1.6 1.6M17.5 17.5l1.6 1.6M19.1 4.9l-1.6 1.6M6.5 17.5l-1.6 1.6" />
        </g>
      </svg>
    );
  }
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" aria-hidden="true">
      <path
        fill="currentColor"
        d="M21 14.6A8.4 8.4 0 0 1 9.4 3 6.8 6.8 0 1 0 21 14.6z"
      />
    </svg>
  );
}
