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

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <I18nProvider>
        <SessionProvider>
          <App />
        </SessionProvider>
      </I18nProvider>
    </BrowserRouter>
  </StrictMode>,
);
