import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { SessionProvider } from './auth';
import { I18nProvider } from './i18n';
import { initialTheme } from './lib/theme';
import './styles.css';

// Stamp the theme before the first render so every route, including login
// outside the app shell, uses it. An explicit stored choice wins; with none,
// the OS color scheme decides (matching the index.html pre-CSS paint).
// Layout keeps data-theme in sync and persists only explicit toggles.
document.documentElement.dataset.theme = initialTheme();

// The production bundle is served below /admin (vite --base=/admin/), while
// the dev server keeps the root base. Strip the trailing slash because React
// Router basenames are path prefixes such as "/admin", not directories.
const routerBasename = import.meta.env.BASE_URL.replace(/\/+$/, '') || undefined;

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter basename={routerBasename}>
      <I18nProvider>
        <SessionProvider>
          <App />
        </SessionProvider>
      </I18nProvider>
    </BrowserRouter>
  </StrictMode>,
);
