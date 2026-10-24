import { StrictMode, useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { Modal } from './Modal';

afterEach(() => { cleanup(); document.body.style.overflow = ''; });

function Inspector() {
  const [open, setOpen] = useState(false);
  return <><button onClick={() => setOpen(true)}>Inspect record</button>
    {open && <Modal label="Record details" onClose={() => setOpen(false)}>
      <button disabled>Unavailable action</button>
      <a href="#evidence">Evidence link</a>
      <button onClick={() => setOpen(false)}>Close details</button>
    </Modal>}
  </>;
}

function openInspector() {
  const opener = screen.getByRole('button', { name: 'Inspect record' });
  opener.focus();
  fireEvent.click(opener);
  return opener;
}

describe('Modal keyboard ownership', () => {
  it('announces the inspector, starts at its content, locks scrolling and restores opener after Escape', () => {
    document.body.style.overflow = 'auto';
    render(<Inspector />);
    const opener = openInspector();
    const dialog = screen.getByRole('dialog', { name: 'Record details' });
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(document.activeElement).toBe(dialog);
    expect(document.body.style.overflow).toBe('hidden');
    fireEvent.keyDown(dialog, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(document.activeElement).toBe(opener);
    expect(document.body.style.overflow).toBe('auto');
  });

  it('cycles Tab and Shift+Tab at both boundaries, skipping disabled controls', () => {
    render(<Inspector />);
    openInspector();
    const dialog = screen.getByRole('dialog');
    const first = screen.getByRole('link', { name: 'Evidence link' });
    const last = screen.getByRole('button', { name: 'Close details' });
    fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(document.activeElement).toBe(first);
    fireEvent.keyDown(first, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(last);
    fireEvent.keyDown(last, { key: 'Tab' });
    expect(document.activeElement).toBe(first);
    dialog.focus();
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(last);
  });

  it('contains outside focus and retains focus when no enabled controls exist', () => {
    const outside = document.createElement('button');
    document.body.append(outside);
    const { unmount } = render(<Modal label="Read-only record" onClose={() => {}}><button disabled>Unavailable</button></Modal>);
    const dialog = screen.getByRole('dialog');
    outside.focus();
    expect(document.activeElement).toBe(dialog);
    fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(document.activeElement).toBe(dialog);
    unmount();
    outside.remove();
  });

  it('uses the current close callback without resetting focus when polling rerenders content', () => {
    const initial = vi.fn();
    const current = vi.fn();
    const { rerender, unmount } = render(<Modal label="Record" onClose={initial}><button>Copy evidence</button></Modal>);
    const control = screen.getByRole('button', { name: 'Copy evidence' });
    control.focus();
    rerender(<Modal label="Record" onClose={current}><button>Copy evidence</button></Modal>);
    expect(document.activeElement).toBe(control);
    fireEvent.keyDown(control, { key: 'Escape' });
    expect(initial).not.toHaveBeenCalled();
    expect(current).toHaveBeenCalledOnce();
    unmount();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(current).toHaveBeenCalledOnce();
  });

  it('restores focus and prior body styles after a close under StrictMode', () => {
    document.body.style.overflow = 'scroll';
    render(<StrictMode><Inspector /></StrictMode>);
    const opener = openInspector();
    fireEvent.click(screen.getByRole('button', { name: 'Close details' }));
    expect(document.activeElement).toBe(opener);
    expect(document.body.style.overflow).toBe('scroll');
  });
});
