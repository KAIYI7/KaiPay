import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { MerchantProvider, useMerchant } from '../../context/MerchantContext';
import { AppLayout } from './AppLayout';

const merchants = [{ id: 'merchant-a', name: 'First assigned merchant' }, { id: 'merchant-b', name: 'Second assigned merchant' }];
vi.mock('../../context/AuthContext', () => ({ useAuth: () => ({ user: { username: 'synthetic.merchant', roles: ['MERCHANT'] }, logout: vi.fn() }) }));
function CurrentMerchant() { const { activeMerchant } = useMerchant(); return <p>Selected: {activeMerchant.id}</p>; }
afterEach(cleanup);
function mount() {
  render(<MerchantProvider memberships={merchants}><MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }} initialEntries={['/dashboard']}>
    <Routes><Route element={<AppLayout />}><Route path="dashboard" element={<CurrentMerchant />} /><Route path="webhooks" element={<p>Webhook destination</p>} /></Route></Routes>
  </MemoryRouter></MerchantProvider>);
}
describe('workspace navigation', () => {
  it('exposes disclosure state and closes navigation after following a route', () => {
    mount(); const toggle = screen.getByRole('button', { name: 'Toggle navigation' });
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(document.getElementById(toggle.getAttribute('aria-controls')!)).toBeTruthy();
    fireEvent.click(toggle); expect(toggle.getAttribute('aria-expanded')).toBe('true');
    fireEvent.click(screen.getByRole('link', { name: 'Webhooks' }));
    expect(screen.getByText('Webhook destination')).toBeTruthy();
    expect(document.activeElement).toBe(screen.getByRole('main'));
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(screen.getByRole('link', { name: 'Skip to content' }).getAttribute('href')).toBe('#main-content');
  });
  it('uses the native labelled merchant selector and updates only to an assigned membership', () => {
    mount(); const select = screen.getByRole('combobox', { name: 'Active merchant' });
    expect(screen.getAllByRole('option').map((option) => option.textContent)).toEqual(merchants.map((item) => item.name));
    fireEvent.change(select, { target: { value: 'merchant-b' } });
    expect(screen.getByText('Selected: merchant-b')).toBeTruthy();
    fireEvent.change(select, { target: { value: 'not-assigned' } });
    expect(screen.getByText('Selected: merchant-b')).toBeTruthy();
  });
});
