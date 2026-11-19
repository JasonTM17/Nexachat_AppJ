import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import LoginPage from '../../src/pages/LoginPage';
import { I18nProvider } from '../../src/i18n';
import { SessionProvider } from '../../src/auth';
import { ApiException } from '../../src/api';

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api')>();
  return {
    ...actual,
    api: vi.fn(),
    login: vi.fn(),
  };
});

import { api } from '../../src/api';

const renderLogin = () =>
  render(
    <MemoryRouter>
      <I18nProvider>
        <SessionProvider>
          <LoginPage />
        </SessionProvider>
      </I18nProvider>
    </MemoryRouter>,
  );

beforeEach(() => {
  sessionStorage.clear();
});

describe('LoginPage', () => {
  it('renders the sign-in form with labels', () => {
    renderLogin();
    expect(screen.getByLabelText('Email or phone')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('shows staff error when login succeeds but /me rejects', async () => {
    const user = userEvent.setup();
    renderLogin();
    (api as ReturnType<typeof vi.fn>).mockRejectedValue(
      new ApiException(403, 'FORBIDDEN', 'no'),
    );
    await user.type(screen.getByLabelText('Email or phone'), '+841234');
    await user.type(screen.getByLabelText('Password'), 'secret');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This account has no admin access',
    );
  });

  it('shows a session-expired notice once when api.ts flagged it', () => {
    sessionStorage.setItem('admin.sessionExpired', '1');
    renderLogin();
    expect(screen.getByRole('status')).toHaveTextContent(
      'Your session has expired — please sign in again.',
    );
    // the flag is consumed, not shown again on the next visit
    expect(sessionStorage.getItem('admin.sessionExpired')).toBeNull();
  });

  it('escapes user input — phone typed stays a value, never HTML', async () => {
    const user = userEvent.setup();
    const { container } = renderLogin();
    await user.type(screen.getByLabelText('Email or phone'), '<img src=x onerror=alert(1)>');
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByLabelText('Email or phone')).toHaveValue('<img src=x onerror=alert(1)>');
  });
});
