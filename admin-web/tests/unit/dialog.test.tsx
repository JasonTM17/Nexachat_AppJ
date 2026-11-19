import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ConfirmDialog } from '../../src/components/ui';
import { I18nProvider } from '../../src/i18n';

describe('ConfirmDialog focus', () => {
  it('focuses the reason field when a reason is required', () => {
    render(
      <I18nProvider>
        <ConfirmDialog title="Suspend user" requireReason onConfirm={vi.fn()} onCancel={vi.fn()} />
      </I18nProvider>,
    );
    expect(screen.getByLabelText(/Reason/)).toHaveFocus();
  });

  it('moves focus into the dialog when no reason is required', () => {
    // Reason-less dialogs have no textarea, so focus must land on the dialog
    // container instead of staying outside the modal.
    render(
      <I18nProvider>
        <ConfirmDialog
          title="Delete rule"
          requireReason={false}
          onConfirm={vi.fn()}
          onCancel={vi.fn()}
        />
      </I18nProvider>,
    );
    expect(screen.getByRole('alertdialog', { name: 'Delete rule' })).toHaveFocus();
  });
});
