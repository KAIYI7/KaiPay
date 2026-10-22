import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { MerchantProvider, useMerchant } from '../../context/MerchantContext';
import { DEV_MERCHANTS } from '../../config/devMerchants';
import { resetSessionTransport } from '../../api/fetchClient';
import { reconciliationClasses, ReconciliationObservation, ReconciliationRun } from '../../models/reconciliation';
import { ReconciliationPage } from './ReconciliationPage';

const merchantA = DEV_MERCHANTS[0].id;
const merchantB = DEV_MERCHANTS[1].id;
const runA = '50000000-0000-0000-0000-000000000001';
const runB = '50000000-0000-0000-0000-000000000002';
const paymentA = '30000000-0000-0000-0000-000000000001';
const paymentB = '30000000-0000-0000-0000-000000000002';
const at = '2026-10-22T04:00:00Z';
const clients: QueryClient[] = [];
function run(id = runA, merchantId = merchantA): ReconciliationRun {
  return { runId: id, merchantId, assessmentScope: 'AUTHORIZATION_V1', ruleset: 'AUTHORIZATION_V1.0', schemaVersion: 1,
    buildRevision: 'test-revision', fromInclusive: '2026-10-21T00:00:00Z', cutoffExclusive: '2026-10-22T03:50:00Z',
    graceSeconds: 300, paymentLimit: 1000, executionLimitSeconds: 15, snapshotObservedAt: at, completedAt: at, cohortCount: 5,
    counts: Object.fromEntries(reconciliationClasses.map((name) => [name, name === 'MATCHED' ? 1 : 0])) as ReconciliationRun['counts'], status: 'COMPLETE' };
}
function observation(id = paymentA): ReconciliationObservation {
  return { paymentId: id, primaryClass: 'FINALIZATION_OUTSTANDING', severity: 'WARNING', reasonCodes: ['AGED_PENDING'],
    explanation: 'A definitive simulator result exists; local finalization was not observed. Reconciliation made no financial changes.',
    evidence: { paymentAmountCents: 101, paymentCurrency: 'MYR', paymentStatus: 'PROCESSING', paymentVersion: 1,
      operationPresent: true, merchantMatches: true, operationAmountCents: 101, operationCurrency: 'MYR', disposition: 'APPROVED',
      evaluationCount: 1, resultAt: at, eventIds: ['40000000-0000-0000-0000-000000000001'], contextIncomplete: false } };
}
function page<T>(items: T[], number = 0, last = true) {
  return { content: items, totalElements: last ? items.length : 21, totalPages: last ? 1 : 2, size: 20, number, first: number === 0, last, empty: !items.length };
}
function response(data: unknown, status = 200) { return new Response(JSON.stringify(status < 400 ? { data } : { message: data }), { status, headers: { 'Content-Type': 'application/json' } }); }
interface Request { path: string; merchant: string | null; signal: AbortSignal | null | undefined; method: string; resolve: (response: Response) => void }
function fixture(hold: (request: Request) => boolean = () => false) {
  const requests: Request[] = [];
  vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((resolve) => {
    const path = String(input); const search = new URL(path, 'http://localhost').searchParams;
    const merchant = new Headers(init?.headers).get('X-Merchant-Id') || search.get('merchantId');
    const request: Request = { path, merchant, signal: init?.signal, method: init?.method || 'GET', resolve }; requests.push(request);
    if (hold(request)) return;
    const id = merchant === merchantB ? runB : runA; const payment = merchant === merchantB ? paymentB : paymentA;
    if (path.includes('/observations')) resolve(response(page([{ ...observation(payment), secret: 'PRIVATE_SECRET', payload: 'PRIVATE_PAYLOAD' }])));
    else if (path.includes(id)) resolve(response(run(id, merchant || merchantA)));
    else resolve(response(page([run(id, merchant || merchantA)])));
  })));
  return requests;
}
function SwitchMerchant() { const { setActiveMerchant } = useMerchant(); return <button onClick={() => setActiveMerchant(DEV_MERCHANTS[1])}>Switch merchant B</button>; }
function mount(admin = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity, staleTime: 30000 } } }); clients.push(client);
  return render(<QueryClientProvider client={client}><MerchantProvider memberships={DEV_MERCHANTS}>
    <MemoryRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}><SwitchMerchant /><ReconciliationPage admin={admin} /></MemoryRouter>
  </MerchantProvider></QueryClientProvider>);
}
afterEach(() => { cleanup(); clients.splice(0).forEach((client) => client.clear()); resetSessionTransport(); vi.unstubAllGlobals(); vi.restoreAllMocks(); });

describe('immutable reconciliation reports', () => {
  it('shows scope, pending evidence and MYR units without exposing unexpected fields or financial actions', async () => {
    const requests = fixture(); const { container } = mount();
    expect(screen.getByText(/Authorization comparison; capture\/refund accounting not assessed/)).toBeTruthy();
    expect(screen.getByText(/cutoff is not a historical balance or settlement date/)).toBeTruthy();
    fireEvent.click(await screen.findByRole('button', { name: `Inspect run ${runA}` }));
    fireEvent.click(await screen.findByRole('button', { name: `Evidence ${paymentA}` }));
    expect(await screen.findAllByText(/RM.*1.01.*101 sen \(MYR\)/)).toHaveLength(2);
    expect(screen.getByText(/FINALIZATION OUTSTANDING · WARNING/)).toBeTruthy();
    expect(screen.getByText(/local finalization was not observed/)).toBeTruthy();
    expect(screen.getByText(/300 second diagnostic grace, 15 second execution deadline/)).toBeTruthy();
    expect(screen.getByRole('link', { name: paymentA }).getAttribute('href')).toBe(`/payments/${paymentA}`);
    expect(container.innerHTML).not.toMatch(/PRIVATE_SECRET|PRIVATE_PAYLOAD/);
    expect(screen.queryByRole('button', { name: /^(run|repair|replay|activate|settle|resolve)/i })).toBeNull();
    expect(requests.every((request) => request.method === 'GET')).toBe(true);
    expect(requests.some((request) => request.path.includes('primaryClass=NON_MATCHED'))).toBe(true);
  });
  it('uses server pagination and class filters, clears evidence and preserves historical units', async () => {
    const requests = fixture((request) => request.path.includes('/observations')); mount();
    fireEvent.click(await screen.findByRole('button', { name: `Inspect run ${runA}` }));
    await waitFor(() => expect(requests.some((request) => request.path.includes('/observations'))).toBe(true));
    await act(async () => requests.find((request) => request.path.includes('/observations'))!.resolve(response(page([observation()], 0, false))));
    fireEvent.click(await screen.findByRole('button', { name: 'Next observations' }));
    await waitFor(() => expect(requests.some((request) => request.path.includes('page=1&size=20&primaryClass=NON_MATCHED'))).toBe(true));
    const next = requests.find((request) => request.path.includes('page=1&size=20&primaryClass=NON_MATCHED'))!;
    await act(async () => next.resolve(response(page([observation()], 1))));
    fireEvent.click(await screen.findByRole('button', { name: `Evidence ${paymentA}` }));
    fireEvent.change(screen.getByLabelText('Observation class'), { target: { value: 'MATCHED' } });
    expect(screen.queryByText('Observed evidence')).toBeNull();
    await waitFor(() => expect(requests.some((request) => request.path.includes('page=0&size=20&primaryClass=MATCHED'))).toBe(true));
    const history = { ...observation(), primaryClass: 'MATCHED' as const, evidence: { ...observation().evidence, paymentCurrency: 'USD', operationCurrency: 'USD' } };
    await act(async () => requests.find((request) => request.path.includes('primaryClass=MATCHED'))!.resolve(response(page([history]))));
    fireEvent.click(await screen.findByRole('button', { name: `Evidence ${paymentA}` }));
    expect(await screen.findAllByText('USD 101 minor units · historical evidence')).toHaveLength(2);
  });
  it('aborts old merchant detail and observations when switching, even if fetch ignores abort', async () => {
    const requests = fixture((request) => request.merchant === merchantA && (request.path.endsWith(runA) || request.path.includes('/observations')));
    const { container } = mount(); fireEvent.click(await screen.findByRole('button', { name: `Inspect run ${runA}` }));
    await waitFor(() => expect(requests.filter((request) => request.path.includes(runA)).length).toBe(2));
    const old = requests.filter((request) => request.path.includes(runA));
    fireEvent.click(screen.getByRole('button', { name: 'Switch merchant B' }));
    await screen.findByRole('button', { name: `Inspect run ${runB}` });
    expect(old.every((request) => request.signal?.aborted)).toBe(true);
    await act(async () => old.forEach((request) => request.resolve(response(request.path.includes('/observations') ? page([observation()]) : run()))));
    expect(container.textContent).not.toContain(runA); expect(container.textContent).not.toContain(paymentA);
    expect(screen.queryByText('Immutable report')).toBeNull();
  });
  it('isolates an abort-ignoring old merchant list from the current merchant', async () => {
    const requests = fixture((request) => request.merchant === merchantA); const { container } = mount();
    await waitFor(() => expect(requests.length).toBe(1)); const old = requests[0];
    fireEvent.click(screen.getByRole('button', { name: 'Switch merchant B' })); await screen.findByRole('button', { name: `Inspect run ${runB}` });
    await act(async () => old.resolve(response(page([run()]))));
    expect(old.signal?.aborted).toBe(true); expect(container.textContent).not.toContain(runA);
  });
  it('requires explicit admin scope, clears it during edits and never links to merchant payments', async () => {
    const requests = fixture(); const { container } = mount(true);
    expect(requests).toHaveLength(0); expect(screen.getByText(/Enter one merchant UUID/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Merchant UUID'), { target: { value: merchantA } }); fireEvent.click(screen.getByRole('button', { name: 'View reports' }));
    fireEvent.click(await screen.findByRole('button', { name: `Inspect run ${runA}` })); await screen.findByRole('button', { name: `Evidence ${paymentA}` });
    expect(screen.queryByRole('link', { name: paymentA })).toBeNull();
    expect(requests.every((request) => request.path.startsWith('/v1/admin/reconciliation/') && request.path.includes(`merchantId=${merchantA}`))).toBe(true);
    fireEvent.change(screen.getByLabelText('Merchant UUID'), { target: { value: merchantB } });
    expect(container.textContent).not.toContain(runA); expect(screen.queryByText('Immutable report')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'View reports' })); await screen.findByRole('button', { name: `Inspect run ${runB}` });
  });
  it('discards late admin results when scope changes before they return', async () => {
    const requests = fixture((request) => request.merchant === merchantA); const { container } = mount(true);
    fireEvent.change(screen.getByLabelText('Merchant UUID'), { target: { value: merchantA } }); fireEvent.click(screen.getByRole('button', { name: 'View reports' }));
    await waitFor(() => expect(requests.length).toBe(1)); const old = requests[0];
    fireEvent.change(screen.getByLabelText('Merchant UUID'), { target: { value: merchantB } }); fireEvent.click(screen.getByRole('button', { name: 'View reports' }));
    await screen.findByRole('button', { name: `Inspect run ${runB}` });
    await act(async () => old.resolve(response(page([run()]))));
    expect(old.signal?.aborted).toBe(true); expect(container.textContent).not.toContain(runA);
  });
  it('provides a safe empty state and a zero-cohort report without a financial-health claim', async () => {
    const requests = fixture(() => true); mount(); await waitFor(() => expect(requests.length).toBe(1));
    await act(async () => requests[0].resolve(response(page([]))));
    expect(await screen.findByText('No operator-created reports.')).toBeTruthy();
    cleanup(); const later = fixture((request) => request.path.includes(runA)); mount();
    fireEvent.click(await screen.findByRole('button', { name: `Inspect run ${runA}` }));
    await waitFor(() => expect(later.filter((request) => request.path.includes(runA)).length).toBe(2));
    await act(async () => later.filter((request) => request.path.includes(runA)).forEach((request) => request.resolve(response(request.path.includes('/observations') ? page([]) : { ...run(), cohortCount: 0 }))));
    expect(await screen.findByText('No eligible records. This report makes no financial-health claim.')).toBeTruthy();
  });
  it('shows sanitized errors and fences an old authenticated lifetime', async () => {
    const requests = fixture(() => true); const { unmount } = mount(); await waitFor(() => expect(requests.length).toBe(1));
    resetSessionTransport(); unmount();
    await act(async () => requests[0].resolve(response(page([run()]))));
    expect(requests[0].signal?.aborted).toBe(true);
    expect(clients[0].getQueryData(['reconciliation', 'merchant', merchantA, 'runs', 0])).toBeUndefined();
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(response('PRIVATE_SERVER_ERROR', 500))));
    const { container } = mount(); expect(await screen.findByRole('alert')).toBeTruthy(); expect(container.innerHTML).not.toContain('PRIVATE_SERVER_ERROR');
  });
});
