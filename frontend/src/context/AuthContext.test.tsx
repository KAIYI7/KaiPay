import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useMutation, useQuery, useQueryClient, QueryClient } from '@tanstack/react-query';
import { AuthProvider, SessionUser, useAuth } from './AuthContext';
import { MerchantProvider, useMerchant } from './MerchantContext';
import { LoginPage } from '../pages/Login/LoginPage';
import { PrincipalQueries } from '../App';
import { ApiError } from '../models/api';
import { apiRequest, resetSessionTransport, setCsrfToken, StaleSessionError } from '../api/fetchClient';
import { App } from '../App';

const sharedMerchant = { id: 'assigned-merchant', name: 'Assigned merchant' };
function user(username: string): SessionUser { return { id: username, username, roles: ['MERCHANT'], merchants: [sharedMerchant] }; }
function response(data: unknown, status = 200) {
  return status === 204 ? new Response(null, { status }) : new Response(JSON.stringify({ data, message: 'server error' }), { status });
}
interface Request { path: string; init: RequestInit; headers: Headers; resolve: (value: Response) => void; reject: (reason: Error) => void }
class Server {
  current: SessionUser | null = null;
  csrf = 'masked-anonymous';
  requests: Request[] = [];
  hold: (request: Request) => boolean = () => false;
  failLogout = false;
  constructor() {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init: RequestInit) => new Promise<Response>((resolve, reject) => {
      const request = { path: String(input), init, headers: new Headers(init.headers), resolve, reject };
      this.requests.push(request);
      if (this.hold(request)) return;
      if (request.path === '/v1/auth/csrf') resolve(response({ token: this.csrf, headerName: 'X-CSRF-TOKEN' }));
      else if (request.path === '/v1/auth/me') resolve(this.current ? response(this.current) : response(null, 401));
      else if (request.path === '/v1/auth/login') {
        expect(request.headers.get('X-CSRF-TOKEN')).toBe(this.csrf);
        const credentials = new URLSearchParams(String(init.body));
        if (credentials.get('password') !== 'valid-password') { resolve(response(null, 401)); return; }
        this.current = user(credentials.get('username')!); this.csrf = 'masked-' + this.current.username;
        resolve(response(null, 204));
      } else if (request.path === '/v1/auth/logout') {
        expect(request.headers.get('X-CSRF-TOKEN')).toBe(this.csrf);
        if (this.failLogout) { resolve(response(null, 500)); return; }
        this.current = null; this.csrf = 'masked-after-logout'; resolve(response(null, 204));
      } else if (request.path === '/v1/private') resolve(response('private-' + this.current?.username));
      else if (request.path === '/v1/mutate') { expect(request.headers.get('X-CSRF-TOKEN')).toBe(this.csrf); resolve(response('mutation-' + this.current?.username)); }
      else if (request.path.startsWith('/v1/events') || request.path.startsWith('/v1/ledger/journals')) resolve(response({ content: [], totalElements: 0 }));
      else if (request.path.startsWith('/v1/payments')) resolve(response({ content: [], totalElements: 0 }));
      else if (request.path === '/v1/ledger/balance') resolve(response({ availableBalanceCents: 0, currency: 'MYR' }));
      else throw new Error('Unexpected route: ' + request.path);
    })));
  }
}
// Simulate two independent browser tabs connected to the same origin channel.
// The remote instance has no access to this tab's React context or transport.
class TabChannel {
  static instances: TabChannel[] = [];
  static messages: unknown[] = [];
  onmessage: ((event: MessageEvent<unknown>) => void) | null = null;
  closed = false;
  constructor(public name: string) { TabChannel.instances.push(this); }
  postMessage(data: unknown) {
    TabChannel.messages.push(data);
    for (const peer of TabChannel.instances) {
      if (peer !== this && peer.name === this.name && !peer.closed) peer.onmessage?.({ data } as MessageEvent<unknown>);
    }
  }
  close() { this.closed = true; }
}
const seenClients: QueryClient[] = [];
function PrivateView() {
  const { user, logout } = useAuth();
  const client = useQueryClient();
  if (!seenClients.includes(client)) seenClients.push(client);
  const { data } = useQuery({ queryKey: ['shared-merchant-private'], queryFn: () => apiRequest<string>('/v1/private') });
  const [result, setResult] = React.useState('');
  const mutation = useMutation({ mutationFn: () => apiRequest<string>('/v1/mutate', { method: 'POST' }), onSuccess: setResult });
  return <><p>Identity {user?.username}</p><p>{data}</p><p>{result}</p>
    <button onClick={() => void logout()}>Sign out</button>
    <button onClick={() => mutation.mutate()}>Mutate</button>
    <button onClick={() => void apiRequest('/v1/forbidden').catch(() => {})}>Forbidden</button></>;
}
function Harness() {
  const { user, initializing, revision } = useAuth();
  if (initializing) return <p>Initializing</p>;
  return user ? <PrincipalQueries key={revision}><PrivateView /></PrincipalQueries> : <LoginPage />;
}
function mount() { render(<AuthProvider><Harness /></AuthProvider>); }
async function login(username: string, password = 'valid-password') {
  await screen.findByText('Sign in to KaiPay');
  fireEvent.change(screen.getByLabelText('Username'), { target: { value: username } });
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } });
  fireEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}
beforeEach(() => { resetSessionTransport(); localStorage.clear(); seenClients.length = 0; TabChannel.instances = []; TabChannel.messages = []; vi.stubGlobal('BroadcastChannel', TabChannel); });
afterEach(() => { cleanup(); seenClients.forEach((client) => client.clear()); vi.unstubAllGlobals(); });

describe('session integration and principal cache lifetime', () => {
  it('bootstraps cookies, logs in with CSRF form, renews token and logs out with session token', async () => {
    const server = new Server(); mount(); await login('alice'); await screen.findByText('private-alice');
    fireEvent.click(screen.getByText('Mutate')); await screen.findByText('mutation-alice');
    fireEvent.click(screen.getByText('Sign out')); await screen.findByText('Sign in to KaiPay');
    await waitFor(() => expect(server.requests.filter((request) => request.path === '/v1/auth/csrf')).toHaveLength(4));
    const loginRequest = server.requests.find((request) => request.path === '/v1/auth/login')!;
    expect(loginRequest.headers.get('Content-Type')).toBe('application/x-www-form-urlencoded');
    expect(loginRequest.headers.has('X-Merchant-Id')).toBe(false);
    expect(server.requests.every((request) => request.init.credentials === 'same-origin' && request.init.cache === 'no-store')).toBe(true);
    expect(localStorage.length).toBe(0);
    expect(seenClients[0].getQueryCache().getAll()).toHaveLength(0);
  });
  it('rejects invalid credentials and clears the password field', async () => {
    const server = new Server(); mount(); await login('alice', 'wrong'); await screen.findByRole('alert');
    expect(server.current).toBeNull(); expect((screen.getByLabelText('Password') as HTMLInputElement).value).toBe('');
  });
  it('restores an existing session without replaying credentials', async () => {
    const server = new Server(); server.current = user('alice'); server.csrf = 'masked-alice'; mount();
    await screen.findByText('private-alice'); expect(server.requests.some((request) => request.path === '/v1/auth/login')).toBe(false);
  });
  it('expires a session on focus and makes a later authenticated request use a fresh cache', async () => {
    const server = new Server(); server.current = user('alice'); mount(); await screen.findByText('private-alice');
    server.current = null; fireEvent(window, new Event('focus')); await screen.findByText(/Your session expired/);
    expect(screen.queryByText('private-alice')).toBeNull(); await login('bob'); await screen.findByText('private-bob');
    expect(seenClients).toHaveLength(2); expect(seenClients[0]).not.toBe(seenClients[1]);
  });
  it.each([200, 401])('discards delayed old read status %s after another user logs in', async (status) => {
    const server = new Server(); server.current = user('alice'); server.hold = (request) => request.path === '/v1/private'; mount();
    await screen.findByText('Identity alice'); const old = server.requests.find((request) => request.path === '/v1/private')!;
    fireEvent.click(screen.getByText('Sign out')); await waitFor(() => expect((screen.getByRole('button', { name: 'Sign in' }) as HTMLButtonElement).disabled).toBe(false));
    server.hold = () => false; await login('bob'); await screen.findByText('private-bob');
    await act(async () => { old.resolve(response('private-alice', status)); });
    expect(screen.getByText('Identity bob')).toBeTruthy(); expect(screen.queryByText('private-alice')).toBeNull();
    expect(seenClients[1].getQueryData(['shared-merchant-private'])).toBe('private-bob');
  });
  it('discards old mutation completion after logout/login into the same merchant', async () => {
    const server = new Server(); server.current = user('alice'); mount(); await screen.findByText('private-alice');
    server.hold = (request) => request.path === '/v1/mutate'; fireEvent.click(screen.getByText('Mutate'));
    await waitFor(() => expect(server.requests.some((request) => request.path === '/v1/mutate')).toBe(true));
    const old = server.requests.find((request) => request.path === '/v1/mutate')!;
    fireEvent.click(screen.getByText('Sign out')); await waitFor(() => expect((screen.getByRole('button', { name: 'Sign in' }) as HTMLButtonElement).disabled).toBe(false));
    server.hold = () => false; await login('bob'); await screen.findByText('private-bob');
    await act(async () => { old.resolve(response('mutation-alice')); });
    expect(screen.queryByText('mutation-alice')).toBeNull(); expect(seenClients[0].getQueryCache().getAll()).toHaveLength(0);
  });
  it('serializes a delayed logout before the next login and refreshes the token', async () => {
    const server = new Server(); server.current = user('alice'); mount(); await screen.findByText('private-alice');
    server.hold = (request) => request.path === '/v1/auth/logout'; fireEvent.click(screen.getByText('Sign out'));
    await screen.findByText('Sign in to KaiPay'); const button = screen.getByRole('button', { name: 'Sign in' }) as HTMLButtonElement;
    expect(button.disabled).toBe(true); fireEvent.click(button);
    expect(server.requests.some((request) => request.path === '/v1/auth/login')).toBe(false);
    const old = server.requests.find((request) => request.path === '/v1/auth/logout')!;
    server.current = null; server.csrf = 'masked-after-delayed-logout';
    await act(async () => { old.resolve(response(null, 204)); }); await waitFor(() => expect(button.disabled).toBe(false));
    await login('bob'); await screen.findByText('private-bob');
  });
  it('reports uncertainty on failed server logout while clearing private client views', async () => {
    const server = new Server(); server.current = user('alice'); server.failLogout = true; mount(); await screen.findByText('private-alice');
    fireEvent.click(screen.getByText('Sign out')); await screen.findByText(/Unable to confirm server logout/);
    expect(server.current?.username).toBe('alice'); expect(screen.queryByText('private-alice')).toBeNull();
  });
  it('keeps forbidden distinct from unauthenticated and does not log out on 403', async () => {
    const server = new Server(); server.current = user('alice'); server.hold = (request) => request.path === '/v1/forbidden'; mount(); await screen.findByText('private-alice');
    const result = apiRequest('/v1/forbidden').catch((error: ApiError) => error);
    server.requests.find((request) => request.path === '/v1/forbidden')!.resolve(response(null, 403));
    expect((await result as ApiError).status).toBe(403); expect(screen.getByText('Identity alice')).toBeTruthy();
    const expired = apiRequest('/v1/forbidden').catch((error: ApiError) => error);
    server.requests.filter((request) => request.path === '/v1/forbidden')[1].resolve(response(null, 401));
    expect((await expired as ApiError).status).toBe(401); await screen.findByText(/Your session expired/);
  });
  it('blocks mutations before CSRF bootstrap and suppresses stale transport results', async () => {
    const server = new Server(); await expect(apiRequest('/v1/mutate', { method: 'POST' })).rejects.toThrow('Refresh the session');
    expect(server.requests).toHaveLength(0); setCsrfToken({ token: 'masked-anonymous', headerName: 'X-CSRF-TOKEN' });
    server.hold = () => true; const pending = apiRequest('/v1/mutate', { method: 'POST' }); resetSessionTransport();
    server.requests[0].resolve(response('old-result')); await expect(pending).rejects.toBeInstanceOf(StaleSessionError);
  });
  it('uses only server memberships despite forged localStorage and renders no merchant operations calls', async () => {
    const server = new Server(); server.current = user('alice'); localStorage.setItem('kaipay_active_merchant_id', 'forged-merchant');
    window.history.replaceState({}, '', '/dashboard'); render(<App />); await screen.findAllByText('Assigned merchant');
    await waitFor(() => expect(server.requests.some((request) => request.path.startsWith('/v1/payments'))).toBe(true));
    expect(server.requests.filter((request) => !request.path.startsWith('/v1/auth')).every((request) => request.headers.get('X-Merchant-Id') === sharedMerchant.id)).toBe(true);
    expect(server.requests.some((request) => request.path.startsWith('/v1/events'))).toBe(false);
    expect(screen.queryByText('DLT Explorer')).toBeNull(); expect(screen.queryByText('Outbox Stream')).toBeNull();
  });
  it('denies unassigned membership selection in context', () => {
    function Switch() {
      const { setActiveMerchant, activeMerchant } = useMerchant();
      return <button onClick={() => {
        expect(() => setActiveMerchant({ id: 'forged', name: 'Forged' })).toThrow('not an assigned membership');
      }}>{activeMerchant.name}</button>;
    }
    render(<MerchantProvider memberships={[sharedMerchant]}><Switch /></MerchantProvider>);
    fireEvent.click(screen.getByText('Assigned merchant')); expect(screen.queryByText('Forged')).toBeNull();
  });
  it('renders admin global ledger without a merchant header or implicit balance requests', async () => {
    const server = new Server(); server.current = { id: 'admin', username: 'operator', roles: ['ADMIN'], merchants: [] };
    window.history.replaceState({}, '', '/ledger'); render(<App />); await screen.findByText('operator');
    await waitFor(() => expect(server.requests.some((request) => request.path.startsWith('/v1/ledger/journals'))).toBe(true));
    expect(server.requests.every((request) => !request.headers.has('X-Merchant-Id'))).toBe(true);
    expect(server.requests.some((request) => request.path === '/v1/ledger/balance')).toBe(false);
    expect(screen.queryByText('Payments')).toBeNull(); expect(screen.getByText('DLT Explorer')).toBeTruthy();
  });
  it('denies direct merchant navigation for admin before any merchant API request', async () => {
    const server = new Server(); server.current = { id: 'admin', username: 'operator', roles: ['ADMIN'], merchants: [] };
    window.history.replaceState({}, '', '/payments/guessed-id'); render(<App />); await screen.findByRole('alert');
    expect(server.requests.every((request) => request.path.startsWith('/v1/auth'))).toBe(true);
  });

  it('broadcasts only non-secret begin/complete notifications for login/logout', async () => {
    const server = new Server(); mount(); await login('alice'); await screen.findByText('private-alice');
    fireEvent.click(screen.getByText('Sign out'));
    await waitFor(() => expect(TabChannel.messages).toEqual([{ phase: 'begin' }, { phase: 'complete' }, { phase: 'begin' }, { phase: 'complete' }]));
    expect(JSON.stringify(TabChannel.messages)).not.toContain('alice');
    expect(server.current).toBeNull();
  });
  it('invalidates the other tab on transition begin, restores B on completion and ignores old 401', async () => {
    const server = new Server(); server.current = user('alice'); mount(); await screen.findByText('private-alice');
    server.hold = (request) => request.path === '/v1/private';
    const staleRead = apiRequest('/v1/private').catch((error: Error) => error);
    const old = server.requests.filter((request) => request.path === '/v1/private')[1];
    const remote = new TabChannel('kaipay-auth-transition');
    act(() => remote.postMessage({ phase: 'begin' }));
    expect(screen.queryByText('Identity alice')).toBeNull(); expect(screen.queryByText('private-alice')).toBeNull();
    expect(seenClients[0].getQueryCache().getAll()).toHaveLength(0);
    expect((screen.getByRole('button', { name: 'Sign in' }) as HTMLButtonElement).disabled).toBe(true);
    // The receiving tab does not rebroadcast, avoiding notification loops.
    expect(TabChannel.messages).toEqual([{ phase: 'begin' }]);
    server.current = user('bob'); server.csrf = 'masked-bob'; server.hold = () => false;
    act(() => remote.postMessage({ phase: 'complete' })); await screen.findByText('private-bob');
    await act(async () => old.resolve(response(null, 401)));
    expect(await staleRead).toBeInstanceOf(StaleSessionError);
    expect(screen.getByText('Identity bob')).toBeTruthy(); expect(screen.queryByText('Identity alice')).toBeNull();
    expect(TabChannel.messages).toEqual([{ phase: 'begin' }, { phase: 'complete' }]);
    fireEvent.click(screen.getByText('Mutate')); await screen.findByText('mutation-bob');
    expect(server.requests.find((request) => request.path === '/v1/mutate')!.headers.get('X-CSRF-TOKEN')).toBe('masked-bob');
  });
  it('hides A immediately while focus revalidation awaits a delayed B response', async () => {
    const server = new Server(); server.current = user('alice'); mount(); await screen.findByText('private-alice');
    server.current = user('bob'); server.csrf = 'masked-bob'; server.hold = (request) => request.path === '/v1/auth/me';
    fireEvent(window, new Event('focus'));
    expect(screen.queryByText('private-alice')).toBeNull(); expect(screen.queryByText('Identity alice')).toBeNull();
    expect(seenClients[0].getQueryCache().getAll()).toHaveLength(0);
    await waitFor(() => expect(server.requests.filter((request) => request.path === '/v1/auth/me')).toHaveLength(2));
    const check = server.requests.filter((request) => request.path === '/v1/auth/me')[1];
    await act(async () => check.resolve(response(user('bob')))); await screen.findByText('private-bob');
    expect(seenClients[1]).not.toBe(seenClients[0]);
  });
  it('leaves private data cleared if focused session revalidation fails over the network', async () => {
    const server = new Server(); server.current = user('alice'); mount(); await screen.findByText('private-alice');
    server.hold = (request) => request.path === '/v1/auth/me'; fireEvent(window, new Event('focus'));
    await waitFor(() => expect(server.requests.filter((request) => request.path === '/v1/auth/me')).toHaveLength(2));
    await act(async () => server.requests.filter((request) => request.path === '/v1/auth/me')[1].reject(new TypeError('connection failed')));
    await screen.findByText(/Unable to verify session/); expect(screen.queryByText('private-alice')).toBeNull();
    expect(seenClients[0].getQueryCache().getAll()).toHaveLength(0);
  });
  it('restores the same user with fresh CSRF and a fresh query client on ordinary focus', async () => {
    const server = new Server(); server.current = user('alice'); mount(); await screen.findByText('private-alice');
    server.csrf = 'new-mask-same-session'; fireEvent(window, new Event('focus'));
    expect(screen.queryByText('private-alice')).toBeNull(); await screen.findByText('private-alice');
    expect(seenClients).toHaveLength(2); expect(server.requests.some((request) => request.path === '/v1/auth/login')).toBe(false);
    fireEvent.click(screen.getByText('Mutate')); await screen.findByText('mutation-alice');
    expect(server.requests.find((request) => request.path === '/v1/mutate')!.headers.get('X-CSRF-TOKEN')).toBe('new-mask-same-session');
  });
  it('fences an obsolete focus response when another tab completes a newer transition', async () => {
    const server = new Server(); server.current = user('alice'); mount(); await screen.findByText('private-alice');
    server.hold = (request) => request.path === '/v1/auth/me'; fireEvent(window, new Event('focus'));
    await waitFor(() => expect(server.requests.filter((request) => request.path === '/v1/auth/me')).toHaveLength(2));
    const obsolete = server.requests.filter((request) => request.path === '/v1/auth/me')[1];
    const remote = new TabChannel('kaipay-auth-transition'); act(() => remote.postMessage({ phase: 'begin' }));
    server.current = user('bob'); server.hold = () => false; act(() => remote.postMessage({ phase: 'complete' }));
    await screen.findByText('private-bob'); await act(async () => obsolete.resolve(response(user('alice'))));
    expect(screen.queryByText('Identity alice')).toBeNull(); expect(screen.getByText('private-bob')).toBeTruthy();
  });
  it('revalidates immediately on becoming visible even when BroadcastChannel is unavailable', async () => {
    vi.stubGlobal('BroadcastChannel', undefined);
    const server = new Server(); server.current = user('alice'); mount(); await screen.findByText('private-alice');
    server.current = user('bob'); fireEvent(document, new Event('visibilitychange'));
    expect(screen.queryByText('private-alice')).toBeNull(); await screen.findByText('private-bob');
  });

});
