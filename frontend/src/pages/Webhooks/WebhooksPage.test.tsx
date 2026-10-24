import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { MerchantProvider, useMerchant } from '../../context/MerchantContext';
import { DEV_MERCHANTS } from '../../config/devMerchants';
import { resetSessionTransport } from '../../api/fetchClient';
import { WebhookDelivery } from '../../models/webhook';
import { WebhooksPage } from './WebhooksPage';

const merchantA = DEV_MERCHANTS[0].id;
const merchantB = DEV_MERCHANTS[1].id;
const eventA = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const eventB = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const at = '2026-10-21T03:00:00Z';
const clients: QueryClient[] = [];
function delivery(eventId: string, state: WebhookDelivery['state'] = 'PENDING'): WebhookDelivery {
  return { id: eventId, eventId, type: 'payment.captured', version: 1,
    paymentId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', refundId: null, state,
    attempts: state === 'PENDING' ? 0 : 2, nextAttemptAt: state === 'PENDING' ? at : null,
    occurredAt: at, createdAt: at, expiresAt: '2026-10-22T03:00:00Z', deliveredAt: state === 'DELIVERED' ? at : null,
    lastResult: state === 'DELIVERED' ? 'ACCEPTED' : null, endpointId: 'revision-' + eventId, keyId: 'key-' + eventId };
}
function page(value: WebhookDelivery, number = 0, last = true) {
  return { content: [value], totalElements: last ? 1 : 21, totalPages: last ? 1 : 2, size: 20, number, first: number === 0, last, empty: false };
}
function response(data: unknown, status = 200) {
  return new Response(JSON.stringify(status < 400 ? { data } : { message: data }), { status, headers: { 'Content-Type': 'application/json' } });
}
interface Request { path: string; merchant: string | null; signal: AbortSignal | null | undefined; resolve: (response: Response) => void }
function httpFixture(hold: (request: Request) => boolean = () => false) {
  const requests: Request[] = [];
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((resolve) => {
    const request: Request = { path: String(input), merchant: new Headers(init?.headers).get('X-Merchant-Id'), signal: init?.signal, resolve };
    requests.push(request);
    if (hold(request)) return;
    const eventId = request.merchant === merchantA ? eventA : eventB;
    if (request.path === '/v1/webhooks/endpoint') resolve(response({ enabled: true, endpointId: 'revision-' + eventId, keyId: 'key-' + eventId }));
    else if (request.path.startsWith('/v1/webhooks/deliveries?')) resolve(response(page(delivery(eventId, 'DELIVERED'))));
    else resolve(response({ delivery: delivery(eventId, 'DELIVERED'), attempts: [
      { ordinal: 1, startedAt: at, completedAt: at, deadline: at, result: 'TIMEOUT', httpStatus: null, durationMs: 10000, keyId: 'key-' + eventId },
      { ordinal: 2, startedAt: at, completedAt: at, deadline: at, result: 'ACCEPTED', httpStatus: 204, durationMs: 15, keyId: 'key-' + eventId },
    ], payload: 'private-payload-marker', secret: 'private-secret-marker', url: 'https://private-host-marker.invalid' }));
  })));
  return requests;
}
function MerchantSwitch() {
  const { setActiveMerchant } = useMerchant();
  return <button onClick={() => setActiveMerchant(DEV_MERCHANTS[1])}>Switch merchant B</button>;
}
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity, staleTime: 30000 } } });
  clients.push(client);
  return render(<QueryClientProvider client={client}><MerchantProvider memberships={DEV_MERCHANTS}>
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><MerchantSwitch /><WebhooksPage /></MemoryRouter>
  </MerchantProvider></QueryClientProvider>);
}
afterEach(() => { cleanup(); clients.splice(0).forEach((client) => client.clear()); resetSessionTransport(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('merchant webhook visibility', () => {
  it('explains empty enrollment without suggesting dispatch is active or showing empty table headings', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => Promise.resolve(String(input).endsWith('/endpoint') ?
      response({ enabled: false, endpointId: null, keyId: null }) : response({ content: [], totalElements: 0, totalPages: 0, number: 0, last: true }))));
    mount();
    expect(await screen.findByText(/No endpoint is enrolled/)).toBeTruthy();
    expect(await screen.findByText('No deliveries match this view.')).toBeTruthy();
    expect(screen.getByText(/Enrollment does not indicate whether runtime dispatch is enabled/)).toBeTruthy();
    expect(screen.queryByRole('table')).toBeNull();
    fireEvent.change(screen.getByLabelText('Delivery state'), { target: { value: 'EXHAUSTED' } });
    expect(await screen.findByText('Try All states to include other delivery outcomes.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /activate|enroll|send|retry now/i })).toBeNull();
  });

  it('shows safe delivery and retry history without exposing unexpected private response fields', async () => {
    httpFixture(); const { container } = mount();
    fireEvent.click(await screen.findByRole('button', { name: `Inspect ${eventA}` }));
    expect(await screen.findByText(/Attempt 1 · TIMEOUT/)).toBeTruthy();
    expect(screen.getByText(/Attempt 2 · ACCEPTED · HTTP 204 · 15 ms/)).toBeTruthy();
    expect(screen.getByText(/DELIVERED · 2\/8/)).toBeTruthy();
    expect(screen.getByText(/more than once or out of order/)).toBeTruthy();
    expect(screen.getByText(/deduplicate the event ID/)).toBeTruthy();
    expect(screen.getByRole('link', { name: delivery(eventA).paymentId }).getAttribute('href')).toBe(`/payments/${delivery(eventA).paymentId}`);
    for (const marker of ['private-payload-marker', 'private-secret-marker', 'private-host-marker']) expect(container.innerHTML).not.toContain(marker);
    expect(screen.queryByRole('button', { name: /retry now|redrive|test send/i })).toBeNull();
  });

  it('aborts a selected detail request and removes its identity when merchant changes', async () => {
    const requests = httpFixture((request) => request.path === `/v1/webhooks/deliveries/${eventA}`);
    const { container } = mount();
    fireEvent.click(await screen.findByRole('button', { name: `Inspect ${eventA}` }));
    await waitFor(() => expect(requests.some((request) => request.path.endsWith(eventA))).toBe(true));
    const pending = requests.find((request) => request.path.endsWith(eventA))!;
    fireEvent.click(screen.getByRole('button', { name: 'Switch merchant B' }));
    expect(await screen.findByRole('button', { name: `Inspect ${eventB}` })).toBeTruthy();
    expect(pending.signal?.aborted).toBe(true);
    await act(async () => pending.resolve(response({ delivery: delivery(eventA), attempts: [] })));
    expect(container.textContent).not.toContain(eventA);
    expect(screen.queryByText('Delivery attempts')).toBeNull();
    expect(requests.filter((request) => request.merchant === merchantB).length).toBe(2);
  });

  it('isolates a late list response from another merchant even when fetch ignores abort', async () => {
    const requests = httpFixture((request) => request.merchant === merchantA && request.path.includes('/deliveries?'));
    const { container } = mount();
    await screen.findByText(/Endpoint enrolled/);
    const pending = requests.find((request) => request.path.includes('/deliveries?'))!;
    fireEvent.click(screen.getByRole('button', { name: 'Switch merchant B' }));
    await screen.findByRole('button', { name: `Inspect ${eventB}` });
    await act(async () => pending.resolve(response(page(delivery(eventA)))));
    expect(container.textContent).not.toContain(eventA);
    expect(pending.signal?.aborted).toBe(true);
  });

  it('uses bounded pagination and state filters and resets details when filters change', async () => {
    const requests = httpFixture((request) => request.path.includes('/deliveries?'));
    mount(); await screen.findByText(/Endpoint enrolled/);
    await act(async () => requests.find((request) => request.path.includes('/deliveries?'))!.resolve(response(page(delivery(eventA), 0, false))));
    fireEvent.click(await screen.findByRole('button', { name: 'Next' }));
    await waitFor(() => expect(requests.some((request) => request.path.includes('page=1&size=20'))).toBe(true));
    const second = requests.find((request) => request.path.includes('page=1&size=20'))!;
    await act(async () => second.resolve(response(page(delivery(eventA, 'EXHAUSTED'), 1))));
    fireEvent.change(screen.getByLabelText('Delivery state'), { target: { value: 'EXHAUSTED' } });
    await waitFor(() => expect(requests.some((request) => request.path.includes('page=0&size=20&state=EXHAUSTED'))).toBe(true));
  });

  it('shows safe errors and the forward-only empty enrollment explanation', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => Promise.resolve(String(input).endsWith('/endpoint') ?
      response({ enabled: false, endpointId: null, keyId: null }) : response('private-error-from-receiver', 500))));
    const { container } = mount();
    expect(await screen.findByText(/historical events are not backfilled/)).toBeTruthy();
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(container.innerHTML).not.toContain('private-error-from-receiver');
  });

  it('discards late responses after session reset and unmount', async () => {
    const requests = httpFixture(() => true); const { unmount } = mount();
    await waitFor(() => expect(requests.length).toBe(2));
    resetSessionTransport(); unmount();
    await act(async () => requests.forEach((request) => request.resolve(response(page(delivery(eventA))))));
    expect(requests.every((request) => request.signal?.aborted)).toBe(true);
    expect(clients[0].getQueryData(['webhooks', merchantA, 'deliveries', 0, ''])).toBeUndefined();
  });
});
