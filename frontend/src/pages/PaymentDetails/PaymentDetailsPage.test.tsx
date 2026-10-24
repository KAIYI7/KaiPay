import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { MerchantProvider, useMerchant } from '../../context/MerchantContext';
import { resetSessionTransport, setCsrfToken } from '../../api/fetchClient';
import { DEV_MERCHANTS } from '../../config/devMerchants';
import { Payment, PaymentStatus } from '../../models/payment';
import { Refund } from '../../models/refund';
import { PaymentDetailsPage } from './PaymentDetailsPage';

const merchantA = DEV_MERCHANTS[0].id;
const merchantB = DEV_MERCHANTS[1].id;
const paymentId = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const paymentPath = `/v1/payments/${paymentId}`;
const storageKey = 'kaipay_active_merchant_id';

function payment(status: PaymentStatus = 'CAPTURED'): Payment {
  return {
    id: paymentId, merchantId: merchantA, customerId: 'customer-only-A',
    amountCents: 10000, currency: 'MYR', status, idempotencyKey: 'payment-key-A',
    metadata: { owner: 'private-metadata-A' }, version: 1,
    createdAt: '2026-10-13T12:00:00Z', updatedAt: '2026-10-13T12:00:00Z',
  };
}

function refund(): Refund {
  return {
    id: 'rrrrrrrr-rrrr-rrrr-rrrr-rrrrrrrrrrrr', paymentId, merchantId: merchantA,
    amountCents: 1000, currency: 'MYR', status: 'COMPLETED', reason: 'refund-only-A',
    idempotencyKey: 'refund-key-A', createdAt: '2026-10-13T12:01:00Z',
  };
}

function response(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(status < 400 ? { data } : { message: data }), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}

interface RequestRecord {
  path: string;
  method: string;
  merchant: string | null;
  headers: Headers;
  body: unknown;
  resolve: (value: Response) => void;
}

class HttpFixture {
  requests: RequestRecord[] = [];
  status: PaymentStatus = 'CAPTURED';
  currency = 'MYR';
  refunds: Refund[] = [refund()];
  hold: (request: RequestRecord) => boolean = () => false;

  constructor() {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const headers = new Headers(init?.headers);
      return new Promise<Response>((resolve) => {
        const request = {
          path: String(input), method: init?.method || 'GET',
          merchant: headers.get('X-Merchant-Id'), headers,
          body: init?.body ? JSON.parse(String(init.body)) : undefined, resolve,
        };
        this.requests.push(request);
        if (this.hold(request)) return;
        if (request.path.startsWith('/v1/events/dlt')) {
          resolve(response({ content: [] }));
        } else if (request.method === 'GET' && request.merchant === merchantB) {
          resolve(response('The payment belongs to another merchant', 404));
        } else if (request.method === 'GET' && request.path === paymentPath) {
          resolve(response({ ...payment(this.status), currency: this.currency }));
        } else if (request.method === 'GET' && request.path === `${paymentPath}/refunds`) {
          resolve(response(this.refunds));
        } else {
          throw new Error(`Unexpected HTTP request: ${request.method} ${request.path}`);
        }
      });
    }));
  }

  matching(path: string, method = 'GET', merchant = merchantA) {
    return this.requests.filter((request) =>
      request.path === path && request.method === method && request.merchant === merchant);
  }
}

function MerchantSwitch() {
  const { setActiveMerchant } = useMerchant();
  return <div>{DEV_MERCHANTS.map((merchant, index) =>
    <button key={merchant.id} onClick={() => setActiveMerchant(merchant)}>
      Switch merchant {index === 0 ? 'A' : 'B'}
    </button>)}</div>;
}

const clients: QueryClient[] = [];
function mount(retry = 0) {
  const client = new QueryClient({ defaultOptions: {
    queries: { retry, retryDelay: 0, staleTime: 30000, gcTime: Infinity, refetchOnWindowFocus: false },
    mutations: { retry: false },
  } });
  clients.push(client);
  render(<QueryClientProvider client={client}>
    <MerchantProvider memberships={DEV_MERCHANTS}>
      <MemoryRouter initialEntries={[`/payments/${paymentId}`]}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <MerchantSwitch />
        <Routes><Route path="/payments/:id" element={<PaymentDetailsPage />} /></Routes>
      </MemoryRouter>
    </MerchantProvider>
  </QueryClientProvider>);
  return client;
}

function switchMerchant(name: 'A' | 'B') {
  fireEvent.click(screen.getByRole('button', { name: `Switch merchant ${name}` }));
}

type Action = 'capture' | 'refund';
function openAction(action: Action) {
  const label = action === 'capture' ? 'Capture Funds' : 'Issue Refund';
  fireEvent.click(screen.getAllByRole('button', { name: label })[0]);
}
function keyInput(action: Action) {
  return screen.getByLabelText(action === 'capture' ?
    'Idempotency Key (Generated Header)' : 'Idempotency Key') as HTMLInputElement;
}
function enterAction(action: Action, key: string, reason = '  customer requested A refund  ') {
  fireEvent.change(keyInput(action), { target: { value: key } });
  if (action === 'refund') {
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '25.00' } });
    fireEvent.change(screen.getByLabelText('Refund Reason (Optional)'), { target: { value: reason } });
  }
}
function submitAction(action: Action) {
  fireEvent.click(screen.getByRole('button', {
    name: action === 'capture' ? 'Confirm & Capture' : 'Process Refund',
  }));
}
function actionPath(action: Action) {
  return `${paymentPath}/${action === 'capture' ? 'capture' : 'refunds'}`;
}
function newRefund(): Refund {
  return { ...refund(), id: 'new-refund-current', amountCents: 2500, reason: 'new-refund-current' };
}
function relatedKey(family: string, merchant: string) {
  return family === 'payments' ? [family, merchant, 'recent'] :
    family === 'ledger-journals' ? [family, merchant, 0, 20] : [family, merchant];
}
function seedRelatedCaches(client: QueryClient) {
  const families = ['payments', 'ledger-balance', 'ledger-journals', 'ledger-journals-summary'];
  for (const merchant of [merchantA, merchantB]) {
    for (const family of families) client.setQueryData(relatedKey(family, merchant), { marker: merchant });
    client.setQueryData(['payments', merchant, 0, 20, ''], { marker: merchant });
    for (const family of ['payment', 'payment-refunds']) {
      client.setQueryData([family, merchant, 'other-payment'], { marker: merchant });
    }
  }
  client.setQueryData(['dlt-events', 0, 20, ''], { content: [] });
  return families;
}
function expectRelatedInvalidation(client: QueryClient, families: string[], invalidated: boolean) {
  for (const family of families) {
    expect(client.getQueryState(relatedKey(family, merchantA))?.isInvalidated).toBe(invalidated);
    expect(client.getQueryState(relatedKey(family, merchantB))?.isInvalidated).toBe(false);
    expect(client.getQueryData(relatedKey(family, merchantB))).toEqual({ marker: merchantB });
  }
  for (const merchant of [merchantA, merchantB]) {
    expect(client.getQueryState(['payments', merchant, 0, 20, ''])?.isInvalidated)
      .toBe(merchant === merchantA ? invalidated : false);
    for (const family of ['payment', 'payment-refunds']) {
      expect(client.getQueryState([family, merchant, 'other-payment'])?.isInvalidated).toBe(false);
      expect(client.getQueryData([family, merchant, 'other-payment'])).toEqual({ marker: merchant });
    }
  }
  expect(client.getQueryState(['dlt-events', 0, 20, ''])?.isInvalidated).toBe(false);
}

async function completeMutation(client: QueryClient, request: RequestRecord, result: Response) {
  await act(async () => { request.resolve(result); });
  await waitFor(() => expect(client.getMutationCache().getAll()[0].state.status).not.toBe('pending'));
}

beforeEach(() => { resetSessionTransport(); setCsrfToken({ token: 'fixture-csrf', headerName: 'X-CSRF-TOKEN' }); localStorage.clear(); localStorage.setItem(storageKey, merchantA); });
afterEach(() => { cleanup(); clients.splice(0).forEach((client) => client.clear()); });

describe('merchant-scoped payment details', () => {
  it('shows B scoped 404 without A details and restores the separate terminal A cache on A -> B -> A', async () => {
    const http = new HttpFixture();
    const client = mount();
    await screen.findByText('refund-only-A');
    expect(screen.getByText(/private-metadata-A/)).toBeTruthy();
    switchMerchant('B');
    expect(screen.queryByText(/private-metadata-A/)).toBeNull();
    expect(screen.queryByText('refund-only-A')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Issue Refund' })).toBeNull();
    await screen.findByText('Payment Not Found');
    expect(http.matching(paymentPath, 'GET', merchantB)).toHaveLength(1);
    expect(http.matching(`${paymentPath}/refunds`, 'GET', merchantB)).toHaveLength(1);
    expect(client.getQueryData(['payment', merchantB, paymentId])).toBeUndefined();
    expect(client.getQueryData(['payment-refunds', merchantB, paymentId])).toBeUndefined();
    switchMerchant('A');
    await screen.findByText('refund-only-A');
    expect(client.getQueryData(['payment', merchantA, paymentId])).toEqual(payment());
    expect(client.getQueryData(['payment-refunds', merchantA, paymentId])).toEqual([refund()]);
    expect(http.matching(paymentPath)).toHaveLength(1);
    expect(http.matching(`${paymentPath}/refunds`)).toHaveLength(1);
  });

  it.each(['payment', 'refunds'] as const)('keeps delayed A %s GET results in A cache after switching to B', async (resource) => {
    const http = new HttpFixture();
    const path = resource === 'payment' ? paymentPath : `${paymentPath}/refunds`;
    http.hold = (request) => request.path === path && request.merchant === merchantA;
    const client = mount();
    await waitFor(() => expect(http.matching(path)).toHaveLength(1));
    const delayed = http.matching(path)[0];
    switchMerchant('B');
    await screen.findByText('Payment Not Found');
    expect(localStorage.getItem(storageKey)).toBe(merchantA); // Selection is now memory-only.
    await act(async () => { delayed.resolve(response(resource === 'payment' ? payment() : [refund()])); });
    await waitFor(() => expect(client.getQueryData([
      resource === 'payment' ? 'payment' : 'payment-refunds', merchantA, paymentId,
    ])).toEqual(resource === 'payment' ? payment() : [refund()]));
    expect(screen.getByText('Payment Not Found')).toBeTruthy();
    expect(screen.queryByText(/private-metadata-A/)).toBeNull();
    expect(screen.queryByText('refund-only-A')).toBeNull();
    expect(client.getQueryData(['payment', merchantB, paymentId])).toBeUndefined();
    expect(client.getQueryData(['payment-refunds', merchantB, paymentId])).toBeUndefined();
    switchMerchant('A');
    await screen.findByText('refund-only-A');
    expect(http.matching(path)).toHaveLength(1);
  });

  it('uses captured merchant headers on detail/refund refetch even when localStorage changes independently', async () => {
    const http = new HttpFixture();
    const client = mount();
    await screen.findByText('refund-only-A');
    localStorage.setItem(storageKey, merchantB);
    await act(async () => {
      await client.refetchQueries({ queryKey: ['payment', merchantA, paymentId], exact: true });
      await client.refetchQueries({ queryKey: ['payment-refunds', merchantA, paymentId], exact: true });
    });
    expect(http.matching(paymentPath)).toHaveLength(2);
    expect(http.matching(`${paymentPath}/refunds`)).toHaveLength(2);
    expect(http.matching(paymentPath, 'GET', merchantB)).toHaveLength(0);
    expect(http.matching(`${paymentPath}/refunds`, 'GET', merchantB)).toHaveLength(0);
    expect(screen.getByText('refund-only-A')).toBeTruthy();
  });

  it.each(['payment', 'refunds'] as const)('retains A identity when a failed %s GET retries after localStorage changes', async (resource) => {
    const http = new HttpFixture();
    const path = resource === 'payment' ? paymentPath : `${paymentPath}/refunds`;
    http.hold = (request) => request.path === path && request.merchant === merchantA;
    const client = mount(1);
    await waitFor(() => expect(http.matching(path)).toHaveLength(1));
    localStorage.setItem(storageKey, merchantB);
    await act(async () => { http.matching(path)[0].resolve(response('temporary server failure', 500)); });
    await waitFor(() => expect(http.matching(path)).toHaveLength(2));
    expect(http.matching(path, 'GET', merchantB)).toHaveLength(0);
    await act(async () => {
      http.matching(path)[1].resolve(response(resource === 'payment' ? payment() : [refund()]));
    });
    await screen.findByText('refund-only-A');
    expect(client.getQueryData([
      resource === 'payment' ? 'payment' : 'payment-refunds', merchantA, paymentId,
    ])).toEqual(resource === 'payment' ? payment() : [refund()]);
    switchMerchant('B');
    await screen.findByText('Payment Not Found');
    expect(screen.queryByText(/private-metadata-A/)).toBeNull();
    expect(client.getQueryData(['payment', merchantB, paymentId])).toBeUndefined();
    expect(client.getQueryData(['payment-refunds', merchantB, paymentId])).toBeUndefined();
  });

  describe.each(['capture', 'refund'] as const)('%s mutation', (action) => {
    it('announces its confirmation dialog, permits Escape before submission and retains pending confirmation', async () => {
      const http = new HttpFixture();
      http.status = action === 'capture' ? 'AUTHORIZED' : 'CAPTURED';
      http.hold = (request) => request.method === 'POST';
      mount(); await screen.findByText(/private-metadata-A/);
      openAction(action);
      const name = action === 'capture' ? 'Capture Payment Funds' : 'Issue Payment Refund';
      const dialog = screen.getByRole('dialog', { name });
      expect(document.activeElement).toBe(dialog);
      fireEvent.keyDown(dialog, { key: 'Escape' });
      expect(screen.queryByRole('dialog')).toBeNull();
      expect(http.matching(actionPath(action), 'POST')).toHaveLength(0);
      openAction(action); enterAction(action, 'keyboard-confirmation'); submitAction(action);
      await waitFor(() => expect(http.matching(actionPath(action), 'POST')).toHaveLength(1));
      fireEvent.keyDown(screen.getByRole('dialog', { name }), { key: 'Escape' });
      expect(screen.getByRole('dialog', { name })).toBeTruthy();
      expect(http.matching(actionPath(action), 'POST')).toHaveLength(1);
    });

    it.each([
      { outcome: 'success', returnToA: false }, { outcome: 'error', returnToA: false },
      { outcome: 'success', returnToA: true }, { outcome: 'error', returnToA: true },
    ])('isolates delayed $outcome after merchant switching (return to A: $returnToA)', async ({ outcome, returnToA }) => {
      const http = new HttpFixture();
      http.status = action === 'capture' ? 'AUTHORIZED' : 'CAPTURED';
      http.hold = (request) => request.method === 'POST';
      const client = mount();
      const families = seedRelatedCaches(client);
      await screen.findByText(/private-metadata-A/);
      await screen.findByText('refund-only-A');
      openAction(action);
      enterAction(action, `old-${action}-key`);
      // Context remains A: the mutable storage fallback must not redirect this submission to B.
      localStorage.setItem(storageKey, merchantB);
      submitAction(action);
      await waitFor(() => expect(http.matching(actionPath(action), 'POST')).toHaveLength(1));
      const pending = http.matching(actionPath(action), 'POST')[0];
      expect(pending.headers.get('Idempotency-Key')).toBe(`old-${action}-key`);
      if (action === 'refund') expect(pending.body).toEqual({ amountCents: 2500, reason: 'customer requested A refund' });
      expect(http.matching(actionPath(action), 'POST', merchantB)).toHaveLength(0);

      switchMerchant('B');
      expect(screen.queryByText(action === 'capture' ? 'Capture Payment Funds' : 'Issue Payment Refund')).toBeNull();
      await screen.findByText('Payment Not Found');
      if (returnToA) {
        switchMerchant('A');
        await screen.findByText('refund-only-A');
        expect(screen.queryByText(action === 'capture' ? 'Capture Payment Funds' : 'Issue Payment Refund')).toBeNull();
        openAction(action);
        expect(keyInput(action).value).not.toBe(`old-${action}-key`);
        if (action === 'refund') {
          expect((screen.getByRole('spinbutton') as HTMLInputElement).value).toBe('90.00');
          expect((screen.getByLabelText('Refund Reason (Optional)') as HTMLInputElement).value).toBe('');
        }
        enterAction(action, `fresh-${action}-key`, 'fresh visit');
      }
      await completeMutation(client, pending, outcome === 'success' ?
        response(action === 'capture' ? payment('CAPTURED') : newRefund()) :
        response(`late-${action}-error`, 409));
      expect(screen.queryByText('Action Completed:')).toBeNull();
      expect(screen.queryByText(`late-${action}-error`)).toBeNull();
      expectRelatedInvalidation(client, families, outcome === 'success');
      expect(client.getQueryData(['payment', merchantB, paymentId])).toBeUndefined();
      expect(client.getQueryData(['payment-refunds', merchantB, paymentId])).toBeUndefined();
      if (returnToA) {
        expect(keyInput(action).value).toBe(`fresh-${action}-key`);
        if (action === 'refund') {
          expect((screen.getByRole('spinbutton') as HTMLInputElement).value).toBe('25.00');
          expect((screen.getByLabelText('Refund Reason (Optional)') as HTMLInputElement).value).toBe('fresh visit');
        }
      } else {
        expect(screen.getByText('Payment Not Found')).toBeTruthy();
        for (const family of ['payment', 'payment-refunds']) {
          expect(client.getQueryState([family, merchantA, paymentId])?.isInvalidated).toBe(outcome === 'success');
        }
        switchMerchant('A');
        await screen.findByText('refund-only-A');
        expect(screen.queryByText('Action Completed:')).toBeNull();
        expect(screen.queryByText(action === 'capture' ? 'Capture Payment Funds' : 'Issue Payment Refund')).toBeNull();
        openAction(action);
        expect(keyInput(action).value).not.toBe(`old-${action}-key`);
        expect(screen.queryByText(`late-${action}-error`)).toBeNull();
      }
    });

    it('preserves current-merchant success behavior and invalidates only its affected caches', async () => {
      const http = new HttpFixture();
      http.status = action === 'capture' ? 'AUTHORIZED' : 'CAPTURED';
      http.hold = (request) => request.method === 'POST';
      const client = mount();
      const families = seedRelatedCaches(client);
      await screen.findByText('refund-only-A');
      openAction(action);
      enterAction(action, `current-${action}-key`);
      submitAction(action);
      await waitFor(() => expect(http.matching(actionPath(action), 'POST')).toHaveLength(1));
      http.status = action === 'capture' ? 'CAPTURED' : 'PARTIALLY_REFUNDED';
      if (action === 'refund') http.refunds = [refund(), newRefund()];
      await completeMutation(client, http.matching(actionPath(action), 'POST')[0],
        response(action === 'capture' ? payment('CAPTURED') : newRefund()));
      await screen.findByText('Action Completed:');
      expect(screen.queryByText(action === 'capture' ? 'Capture Payment Funds' : 'Issue Payment Refund')).toBeNull();
      expectRelatedInvalidation(client, families, true);
      await waitFor(() => expect(http.matching(paymentPath).length).toBeGreaterThan(1));
      await waitFor(() => expect(http.matching(`${paymentPath}/refunds`).length).toBeGreaterThan(1));
      if (action === 'refund') {
        await screen.findByText('new-refund-current');
        openAction(action);
        expect((screen.getByRole('spinbutton') as HTMLInputElement).value).toBe('65.00');
        expect((screen.getByLabelText('Refund Reason (Optional)') as HTMLInputElement).value).toBe('');
        expect(keyInput(action).value).not.toBe(`current-${action}-key`);
      }
      switchMerchant('B');
      expect(screen.queryByText('Action Completed:')).toBeNull();
    });

    it('resets a visible API error and edited form on merchant switching', async () => {
      const http = new HttpFixture();
      http.status = action === 'capture' ? 'AUTHORIZED' : 'CAPTURED';
      http.hold = (request) => request.method === 'POST';
      const client = mount();
      await screen.findByText('refund-only-A');
      openAction(action);
      enterAction(action, `failed-${action}-key`);
      submitAction(action);
      await waitFor(() => expect(http.matching(actionPath(action), 'POST')).toHaveLength(1));
      await completeMutation(client, http.matching(actionPath(action), 'POST')[0], response('current API error', 409));
      await screen.findByText('current API error');
      switchMerchant('B');
      expect(screen.queryByText('current API error')).toBeNull();
      await screen.findByText('Payment Not Found');
      switchMerchant('A');
      await screen.findByText('refund-only-A');
      expect(screen.queryByText(action === 'capture' ? 'Capture Payment Funds' : 'Issue Payment Refund')).toBeNull();
      openAction(action);
      expect(screen.queryByText('current API error')).toBeNull();
      expect(keyInput(action).value).not.toBe(`failed-${action}-key`);
      if (action === 'refund') {
        expect((screen.getByRole('spinbutton') as HTMLInputElement).value).toBe('90.00');
        expect((screen.getByLabelText('Refund Reason (Optional)') as HTMLInputElement).value).toBe('');
      }
    });
  });
});

describe('currency-safe payment details', () => {
  it.each(['AUTHORIZED', 'CAPTURED'] as const)('preserves historical USD display and blocks new %s financial actions', async (status) => {
    const http = new HttpFixture();
    http.status = status;
    http.currency = 'USD';
    http.refunds = [{ ...refund(), currency: 'USD' }];
    mount();
    await screen.findByText('Historical USD record. New captures and refunds support MYR only.');
    expect(screen.queryByRole('button', { name: 'Capture Funds' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Issue Refund' })).toBeNull();
    expect(screen.getAllByText(/US\$/).length).toBeGreaterThan(0);
  });

  it('does not combine mixed refund units or offer an incompatible refund', async () => {
    const http = new HttpFixture();
    http.refunds = [refund(), { ...refund(), id: 'usd-refund', currency: 'USD', amountCents: 5000 }];
    mount();
    await screen.findByText('Refund currency mismatch. Financial totals and new refunds are unavailable.');
    expect(screen.queryByRole('button', { name: 'Issue Refund' })).toBeNull();
    expect(screen.getAllByText('Unavailable: currency mismatch').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/US\$/).length).toBeGreaterThan(0);
  });

  it('rejects fractional sen in refund input without submitting a request', async () => {
    const http = new HttpFixture();
    mount();
    await screen.findByText('refund-only-A');
    openAction('refund');
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '1.005' } });
    fireEvent.submit(screen.getByRole('button', { name: 'Process Refund' }).closest('form')!);
    expect(await screen.findByText(/Please enter a positive MYR refund amount/)).toBeTruthy();
    expect(http.matching(actionPath('refund'), 'POST')).toHaveLength(0);
  });
});
