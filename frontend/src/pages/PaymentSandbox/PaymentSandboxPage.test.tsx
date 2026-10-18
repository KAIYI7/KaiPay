import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { MerchantProvider } from '../../context/MerchantContext';
import { DEV_MERCHANTS } from '../../config/devMerchants';
import { paymentsApi } from '../../api/paymentsApi';
import { PaymentSandboxPage } from './PaymentSandboxPage';

const clients: QueryClient[] = [];
afterEach(() => { cleanup(); clients.splice(0).forEach((client) => client.clear()); vi.restoreAllMocks(); });
function mount() {
  const create = vi.spyOn(paymentsApi, 'createPayment').mockImplementation(() => new Promise(() => {}));
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  clients.push(client);
  render(<QueryClientProvider client={client}><MerchantProvider memberships={DEV_MERCHANTS}>
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><PaymentSandboxPage /></MemoryRouter>
  </MerchantProvider></QueryClientProvider>);
  return create;
}

describe('MYR payment sandbox', () => {
  it('sends MYR and exact sen with the originating merchant', async () => {
    const create = mount();
    expect((screen.getByLabelText('Currency') as HTMLInputElement).readOnly).toBe(true);
    fireEvent.change(screen.getByLabelText('Amount (MYR)'), { target: { value: '1.01' } });
    fireEvent.click(screen.getByRole('button', { name: /1\. Test Idempotent Replay/ }));
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(create.mock.calls[0][1]).toMatchObject({ amountCents: 101, currency: 'MYR' });
    expect(create.mock.calls[0][2]).toBe(DEV_MERCHANTS[0].id);
  });

  it.each(['', '0', '-1', '1.005', '90071992547409.92'])(
    'rejects %s without silently substituting or rounding a payment', (input) => {
      const create = mount();
      fireEvent.change(screen.getByLabelText('Amount (MYR)'), { target: { value: input } });
      fireEvent.click(screen.getByRole('button', { name: /1\. Test Idempotent Replay/ }));
      expect(create).not.toHaveBeenCalled();
      expect(screen.getByText(/Enter a positive MYR amount/)).toBeTruthy();
    });

  it('retains the RM25 payload conflict scenario in integer sen', async () => {
    const create = mount();
    fireEvent.change(screen.getByLabelText('Amount (MYR)'), { target: { value: '1.01' } });
    fireEvent.click(screen.getByRole('button', { name: /2\. Test Payload Conflict/ }));
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(create.mock.calls[0][1]).toMatchObject({ amountCents: 2601, currency: 'MYR' });
  });
});
