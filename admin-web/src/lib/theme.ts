// Theme is a tri-state: an explicit user choice ('light'/'dark' in
// localStorage) always wins; with nothing stored the OS preference decides.
// The choice is persisted ONLY after an explicit toggle, so first-run
// OS-dark users are not locked into the light theme.
export const THEME_KEY = 'admin.theme';

export function initialTheme(): 'light' | 'dark' {
  const stored = localStorage.getItem(THEME_KEY);
  if (stored === 'dark' || stored === 'light') return stored;
  // matchMedia is optional-chained twice: jsdom has no matchMedia at all.
  return window.matchMedia?.('(prefers-color-scheme: dark)')?.matches ? 'dark' : 'light';
}

export function persistTheme(theme: 'light' | 'dark') {
  localStorage.setItem(THEME_KEY, theme);
}
