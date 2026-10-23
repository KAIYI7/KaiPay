import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { MerchantProvider } from '../../context/MerchantContext';
import { DEV_MERCHANTS } from '../../config/devMerchants';
import { ledgerApi } from '../../api/ledgerApi';
import { Journal } from '../../models/ledger';
import { LedgerPage } from './LedgerPage';

vi.mock('../../context/AuthContext', () => ({ useAuth: () => ({ user: { roles: ['ADMIN'] } }) }));
const clients: QueryClient[] = [];
afterEach(() => { cleanup(); clients.splice(0).forEach((client) => client.clear()); vi.restoreAllMocks(); });
function journal(currencies = ['USD', 'USD']): Journal {
  return {
    id: 'historical-journal', journalNumber: 'J-HISTORICAL', sourceType: 'PAYMENT_CAPTURE', sourceId: 'payment',
    description: 'Historical journal', postedAt: '2026-10-13T12:00:00Z', totalDebitCents: 1050, totalCreditCents: 1050,
    entries: currencies.map((currency, index) => ({ id: String(index), accountNumber: String(index), accountName: 'Test account',
      accountType: 'ASSET', entryType: index === 0 ? 'DEBIT' : 'CREDIT', amountCents: 1050, currency })),
  };
}
function mount(value?: Journal) {
  vi.spyOn(ledgerApi, 'listJournals').mockResolvedValue({ content: value ? [value] : [], totalElements: value ? 1 : 0, totalPages: value ? 1 : 0, size: 10, number: 0, first: true, last: true, empty: !value });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  clients.push(client);
  render(<QueryClientProvider client={client}><MerchantProvider memberships={DEV_MERCHANTS}>
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><LedgerPage /></MemoryRouter>
  </MerchantProvider></QueryClientProvider>);
}

describe('ledger currency displays', () => {
  it('keeps the operator empty state out of merchant payment routes', async () => {
    mount();
    await screen.findByText('No financial journals found');
    expect(screen.queryByRole('link', { name: 'Go to Payments Explorer' })).toBeNull();
  });

  it('keeps historical USD totals and the balancing difference in USD', async () => {
    mount(journal());
    await screen.findByText('J-HISTORICAL');
    expect(screen.getAllByText(/US\$.*10\.50/)).toHaveLength(2);
    expect(screen.queryByText(/RM.*10\.50/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Inspect' }));
    expect(screen.getByText(/Net Difference: US\$/)).toBeTruthy();
  });

  it.each(['mixed', 'empty', 'unavailable', 'omitted', 'nan', 'infinity', 'negative-infinity'] as const)('never claims balanced or displays synthetic MYR totals for %s journal data', async (kind) => {
    const value = journal(kind === 'mixed' ? ['MYR', 'USD'] : kind === 'empty' ? [] : ['MYR', 'MYR']);
    if (kind === 'unavailable') { value.totalDebitCents = null; value.totalCreditCents = null; }
    if (kind === 'omitted') { delete value.totalDebitCents; delete value.totalCreditCents; }
    if (kind === 'nan') { value.totalDebitCents = NaN; value.totalCreditCents = NaN; }
    if (kind === 'infinity') { value.totalDebitCents = Infinity; value.totalCreditCents = Infinity; }
    if (kind === 'negative-infinity') { value.totalDebitCents = -Infinity; value.totalCreditCents = -Infinity; }
    mount(value);
    await screen.findByText('J-HISTORICAL');
    expect(screen.queryByText('Balanced')).toBeNull();
    expect(screen.getAllByText('Unavailable: incomplete or inconsistent journal')).toHaveLength(2);
    expect(screen.queryByText(/NaN|∞/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Inspect' }));
    expect(screen.queryByText(/Double-Entry Invariant Satisfied/)).toBeNull();
    expect(screen.getByText(/Journal invariant unavailable or violated/)).toBeTruthy();
    expect(screen.queryByText(/Net Difference:/)).toBeNull();
  });
});
