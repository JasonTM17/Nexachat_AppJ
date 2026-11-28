import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { ConfirmDialog, Drawer } from '../../src/components/ui';
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

  it('restores focus one layer at a time when a confirmation sits over a drawer', async () => {
    function Example() {
      const [drawerOpen, openDrawer] = useState(false);
      const [confirmOpen, openConfirm] = useState(false);
      return (
        <>
          <button onClick={() => openDrawer(true)}>Open user</button>
          {drawerOpen && (
            <Drawer title="User details" onClose={() => openDrawer(false)}>
              <button onClick={() => openConfirm(true)}>Suspend</button>
            </Drawer>
          )}
          {confirmOpen && (
            <ConfirmDialog
              title="Suspend user"
              requireReason
              onConfirm={vi.fn()}
              onCancel={() => openConfirm(false)}
            />
          )}
        </>
      );
    }

    const user = userEvent.setup();
    render(<I18nProvider><Example /></I18nProvider>);
    const opener = screen.getByRole('button', { name: 'Open user' });
    await user.click(opener);
    const suspend = screen.getByRole('button', { name: /^Suspend$/ });
    await user.click(suspend);
    expect(screen.getByLabelText(/Reason/)).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'User details' })).toBeInTheDocument();
    expect(suspend).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('keeps focus on the dialog and skips disabled textarea when Tab is pressed in a busy ConfirmDialog', async () => {
    const user = userEvent.setup();
    render(
      <I18nProvider>
        <ConfirmDialog title="Suspend user" requireReason busy onConfirm={vi.fn()} onCancel={vi.fn()} />
      </I18nProvider>,
    );

    const dialog = screen.getByRole('alertdialog', { name: 'Suspend user' });
    const textarea = screen.getByLabelText(/Reason/);
    expect(textarea).toBeDisabled();
    expect(dialog).toHaveFocus();

    await user.tab();
    expect(textarea).not.toHaveFocus();
    expect(dialog).toHaveFocus();
  });
});
