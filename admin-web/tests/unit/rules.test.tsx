import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RulesPage from '../../src/pages/RulesPage';
import { I18nProvider } from '../../src/i18n';
import { SessionProvider } from '../../src/auth';
import { ApiException, type AlertRule } from '../../src/api';

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api')>();
  return { ...actual, api: vi.fn() };
});

import { api } from '../../src/api';

const apiMock = api as ReturnType<typeof vi.fn>;

const me = { userId: 'me-admin', role: 'ADMIN', displayName: 'Root' };

const rule = (over: Partial<AlertRule> = {}): AlertRule => ({
  id: 'r1',
  name: 'phish-en',
  category: 'SCAM_PHISHING',
  severity: 'HIGH',
  patternType: 'KEYWORD',
  pattern: 'password',
  locale: 'en',
  builtIn: false,
  enabled: true,
  description: 'catches phishing',
  ...over,
});

// Route api calls by path + method so reads and writes to the same endpoint
// can be stubbed independently of call order.
const stubRoutes = (rules: AlertRule[] = [rule()]) =>
  apiMock.mockImplementation((path: string, opts?: { method?: string }) => {
    if (path === '/api/v1/admin/me') return Promise.resolve(me);
    if (path === '/api/v1/admin/rules' && (!opts || opts.method === undefined)) {
      return Promise.resolve(rules);
    }
    return Promise.reject(new ApiException(404, 'NO_ROUTE', `unstubbed ${path}`));
  });

const renderPage = ({ withSession = true } = {}) => {
  if (withSession) sessionStorage.setItem('admin.accessToken', 'tok');
  return render(
    <I18nProvider>
      <SessionProvider>
        <RulesPage />
      </SessionProvider>
    </I18nProvider>,
  );
};

beforeEach(() => {
  apiMock.mockReset();
  localStorage.clear();
  sessionStorage.clear();
});

describe('RulesPage', () => {
  it('lists rules with category, type, pattern and enabled state', async () => {
    stubRoutes([rule(), rule({ id: 'r2', name: 'self-harm-vi', builtIn: true, enabled: false })]);
    const { container } = renderPage();

    expect(await screen.findByText('phish-en')).toBeInTheDocument();
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/rules');
    const row = screen.getByText('phish-en').closest('tr') as HTMLElement;
    expect(within(row).getByText('SCAM_PHISHING')).toBeInTheDocument();
    expect(within(row).getByText('KEYWORD')).toBeInTheDocument();
    expect(within(row).getByText('password')).toBeInTheDocument();
    expect(
      screen.getByRole('checkbox', { name: 'Enabled phish-en' }),
    ).toBeChecked();
    expect(screen.getByRole('checkbox', { name: 'Enabled self-harm-vi' })).not.toBeChecked();
    // built-in marker resolves through i18n
    expect(screen.getByText('Built-in')).toBeInTheDocument();
    expect(container.textContent).not.toMatch(/\b(rules|common)\.[a-z]/);
  });

  it('shows the empty state', async () => {
    stubRoutes([]);
    renderPage();
    expect(await screen.findByText('Nothing here')).toBeInTheDocument();
  });

  it('disables a rule and reloads', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();

    apiMock.mockResolvedValueOnce(undefined);
    await user.click(await screen.findByRole('checkbox', { name: 'Enabled phish-en' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/rules/r1', {
        method: 'PATCH',
        body: { enabled: false },
      }),
    );
    // reload after the PATCH
    await waitFor(() =>
      expect(apiMock.mock.calls.filter(([p]) => p === '/api/v1/admin/rules').length).toBe(2),
    );
  });

  it('changes severity inline', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();

    apiMock.mockResolvedValueOnce(undefined);
    await user.selectOptions(await screen.findByLabelText('Severity phish-en'), 'CRITICAL');
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/rules/r1', {
        method: 'PATCH',
        body: { severity: 'CRITICAL' },
      }),
    );
  });

  it('creates a rule from the form', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();
    await screen.findByText('phish-en');

    await user.click(screen.getByRole('button', { name: 'New rule' }));
    const nameInput = screen.getByLabelText('Name');
    const formCard = nameInput.closest('.card') as HTMLElement;
    const save = within(formCard).getByRole('button', { name: 'Save' });
    expect(save).toBeDisabled();

    await user.type(nameInput, 'my-rule');
    await user.type(within(formCard).getByLabelText('Pattern'), 'seed phrase');
    expect(save).toBeEnabled();

    apiMock.mockResolvedValueOnce(rule({ id: 'r9', name: 'my-rule' }));
    await user.click(save);
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith(
        '/api/v1/admin/rules',
        expect.objectContaining({
          method: 'POST',
          body: expect.objectContaining({
            name: 'my-rule',
            category: 'CUSTOM',
            severity: 'MEDIUM',
            patternType: 'KEYWORD',
            pattern: 'seed phrase',
          }),
        }),
      ),
    );
  });

  it('deletes a non-built-in rule after confirmation', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();
    await screen.findByText('phish-en');

    await user.click(screen.getByRole('button', { name: 'Delete' }));
    const dialog = screen.getByRole('alertdialog', { name: 'Delete phish-en' });
    apiMock.mockResolvedValueOnce(undefined);
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/rules/r1', { method: 'DELETE' }),
    );
  });

  it('runs a pattern test and shows the match snippet', async () => {
    const user = userEvent.setup();
    stubRoutes();
    renderPage();
    await screen.findByText('phish-en');

    const card = screen.getByText('Test pattern').closest('.card') as HTMLElement;
    const run = within(card).getByRole('button', { name: 'Run' });
    expect(run).toBeDisabled();

    await user.type(within(card).getByLabelText('Pattern'), 'password');
    await user.type(within(card).getByLabelText('Sample text'), 'give me your password now');
    expect(run).toBeEnabled();

    apiMock.mockResolvedValueOnce({
      matched: true,
      matches: [{ offset: 14, length: 8, snippet: 'your password now' }],
    });
    await user.click(run);
    await waitFor(() =>
      expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/rules/test', {
        method: 'POST',
        body: { patternType: 'KEYWORD', pattern: 'password', sampleText: 'give me your password now' },
      }),
    );
    expect(await within(card).findByText('Matched')).toBeInTheDocument();
    expect(within(card).getByText('your password now')).toBeInTheDocument();
  });

  it('keeps the table read-only for non-admin moderators', async () => {
    stubRoutes();
    renderPage({ withSession: false });

    await screen.findByText('phish-en');
    expect(screen.queryByRole('button', { name: 'New rule' })).toBeNull();
    expect(screen.getByRole('checkbox', { name: 'Enabled phish-en' })).toBeDisabled();
    expect(screen.queryByLabelText('Severity phish-en')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete' })).toBeNull();
  });
});
