import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import Layout from '../../src/components/Layout';
import { I18nProvider } from '../../src/i18n';
import { SessionProvider } from '../../src/auth';
import { THEME_KEY } from '../../src/lib/theme';

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api')>();
  return { ...actual, api: vi.fn() };
});

import { api } from '../../src/api';

const apiMock = api as ReturnType<typeof vi.fn>;

const renderLayout = (route: string) => {
  sessionStorage.setItem('admin.accessToken', 'tok');
  apiMock.mockImplementation((path: string) => {
    if (path === '/api/v1/admin/me') {
      return Promise.resolve({ userId: 'me-admin', role: 'ADMIN', displayName: 'Root' });
    }
    return Promise.reject(new Error(`unstubbed ${path}`));
  });
  return render(
    <MemoryRouter initialEntries={[route]}>
      <I18nProvider>
        <SessionProvider>
          <Routes>
            <Route element={<Layout />}>
              <Route path="/overview" element={<div>overview-page</div>} />
              <Route path="/users" element={<div>users-page</div>} />
            </Route>
          </Routes>
        </SessionProvider>
      </I18nProvider>
    </MemoryRouter>,
  );
};

beforeEach(() => {
  apiMock.mockReset();
  localStorage.clear();
  sessionStorage.clear();
  document.title = '';
  document.documentElement.dataset.theme = '';
  delete (window as unknown as Record<string, unknown>).matchMedia;
});

describe('Layout', () => {
  it('sets a per-route document title from the TITLES map', async () => {
    renderLayout('/overview');
    await screen.findByText('overview-page');
    await waitFor(() => expect(document.title).toBe('Overview · NexaChat Admin'));
  });

  it('updates the document title when the route changes', async () => {
    renderLayout('/overview');
    await screen.findByText('overview-page');
    await waitFor(() => expect(document.title).toBe('Overview · NexaChat Admin'));
    // Navigate within the same shell via the sidebar link.
    await userEvent.click(screen.getByRole('link', { name: 'Users' }));
    await screen.findByText('users-page');
    await waitFor(() => expect(document.title).toBe('Users · NexaChat Admin'));
  });

  it('honors the OS dark scheme on first run without persisting it', async () => {
    window.matchMedia = vi.fn().mockReturnValue({ matches: true });
    renderLayout('/overview');
    await screen.findByText('overview-page');
    await waitFor(() =>
      expect(document.documentElement.dataset.theme).toBe('dark'),
    );
    expect(localStorage.getItem(THEME_KEY)).toBeNull();
  });

  it('persists the theme only after an explicit toggle', async () => {
    window.matchMedia = vi.fn().mockReturnValue({ matches: false });
    renderLayout('/overview');
    await screen.findByText('overview-page');
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(localStorage.getItem(THEME_KEY)).toBeNull();

    const toggle = screen.getByRole('button', { name: 'Toggle theme' });
    await userEvent.click(toggle);
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(localStorage.getItem(THEME_KEY)).toBe('dark');

    await userEvent.click(toggle);
    expect(document.documentElement.dataset.theme).toBe('light');
    expect(localStorage.getItem(THEME_KEY)).toBe('light');
  });
});
