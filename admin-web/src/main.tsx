import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { SessionProvider } from './auth';
import { I18nProvider } from './i18n';
import './styles.css';

// Stamp the persisted theme before the first render so every route, including
// login outside the app shell, uses it; Layout keeps it in sync on toggle.
document.documentElement.dataset.theme =
  localStorage.getItem('admin.theme') === 'dark' ? 'dark' : 'light';

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
