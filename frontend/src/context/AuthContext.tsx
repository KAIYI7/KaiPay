import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { apiRequest, onUnauthorized, resetSessionTransport, setCsrfToken } from '../api/fetchClient';
import { ApiError } from '../models/api';

export interface MerchantMembership { id: string; name: string }
export interface SessionUser { id: string; username: string; roles: string[]; merchants: MerchantMembership[] }
interface AuthState {
  user: SessionUser | null;
  initializing: boolean;
  revision: number;
  notice: string;
  transitioning: boolean;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
}
const AuthContext = createContext<AuthState | undefined>(undefined);
async function refreshCsrf() {
  setCsrfToken(await apiRequest<{ token: string; headerName: string }>('/v1/auth/csrf', { authRequest: true }));
}
async function readUser() { return apiRequest<SessionUser>('/v1/auth/me', { authRequest: true }); }

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [initializing, setInitializing] = useState(true);
  const [revision, setRevision] = useState(0);
  const [notice, setNotice] = useState('');
  const [transitioning, setTransitioning] = useState(false);
  const currentUser = useRef<SessionUser | null>(null);
  const lifetime = useRef(0);
  const mounted = useRef(false);
  const transition = useRef(false);
  const remoteTransition = useRef(false);
  const channel = useRef<BroadcastChannel | null>(null);
  const clearIdentity = (message = '') => {
    lifetime.current++;
    resetSessionTransport();
    currentUser.current = null;
    setUser(null);
    setRevision((value) => value + 1);
    setNotice(message);
  };
  const restoreIdentity = (identity: SessionUser) => {
    currentUser.current = identity;
    setUser(identity);
  };
  const notifyTransition = (phase: 'begin' | 'complete') => {
    // Deliberately no user IDs, cookies, credentials or tokens in this channel.
    channel.current?.postMessage({ phase });
  };
  const revalidate = async (bootstrap = false) => {
    if (transition.current || remoteTransition.current) return;
    // Shared cookies may now belong to another tab's principal. Hide/fence old
    // views before any asynchronous network check rather than while awaiting it.
    clearIdentity();
    setInitializing(true);
    const requestLifetime = lifetime.current;
    try {
      await refreshCsrf();
      if (requestLifetime !== lifetime.current || !mounted.current) return;
      const identity = await readUser();
      if (requestLifetime === lifetime.current && mounted.current) restoreIdentity(identity);
    } catch (error) {
      if (requestLifetime !== lifetime.current || !mounted.current) return;
      setNotice(error instanceof ApiError && error.status === 401 ?
        (bootstrap ? '' : 'Your session expired. Please sign in again.') :
        'Unable to verify session. Private data was cleared. Reload to retry.');
    } finally {
      if (requestLifetime === lifetime.current && mounted.current) setInitializing(false);
    }
  };

  useEffect(() => {
    mounted.current = true;
    if (typeof BroadcastChannel !== 'undefined') {
      channel.current = new BroadcastChannel('kaipay-auth-transition');
      channel.current.onmessage = (event: MessageEvent<unknown>) => {
        const phase = (event.data as { phase?: unknown } | null)?.phase;
        if (phase === 'begin') {
          remoteTransition.current = true;
          clearIdentity('Session changed in another tab. Waiting for the operation to finish.');
          setInitializing(false);
          setTransitioning(true);
        } else if (phase === 'complete') {
          remoteTransition.current = false;
          setTransitioning(transition.current);
          void revalidate();
        }
      };
    }
    void revalidate(true);
    const unsubscribe = onUnauthorized(() => clearIdentity('Your session expired. Please sign in again.'));
    const check = () => { if (currentUser.current) void revalidate(); };
    const visible = () => { if (document.visibilityState === 'visible') check(); };
    window.addEventListener('focus', check);
    document.addEventListener('visibilitychange', visible);
    return () => {
      mounted.current = false;
      lifetime.current++;
      resetSessionTransport();
      unsubscribe();
      window.removeEventListener('focus', check);
      document.removeEventListener('visibilitychange', visible);
      channel.current?.close();
      channel.current = null;
    };
  }, []);

  const login = async (username: string, password: string) => {
    if (transition.current || remoteTransition.current) throw new Error('An authentication transition is already in progress.');
    transition.current = true; setTransitioning(true);
    notifyTransition('begin');
    try {
      clearIdentity();
      const requestLifetime = lifetime.current;
      await refreshCsrf();
      if (requestLifetime !== lifetime.current || !mounted.current) return;
      await apiRequest<void>('/v1/auth/login', { authRequest: true, method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ username, password }).toString() });
      await refreshCsrf();
      if (requestLifetime !== lifetime.current || !mounted.current) return;
      const identity = await readUser();
      if (requestLifetime === lifetime.current && mounted.current) restoreIdentity(identity);
    } finally {
      transition.current = false;
      if (mounted.current) setTransitioning(remoteTransition.current);
      notifyTransition('complete');
    }
  };
  const logout = async () => {
    if (transition.current || remoteTransition.current) return;
    transition.current = true; setTransitioning(true);
    notifyTransition('begin');
    // Fence every old request immediately, retaining CSRF only for logout POST.
    lifetime.current++;
    resetSessionTransport(false);
    currentUser.current = null;
    setUser(null);
    setRevision((value) => value + 1);
    try {
      await apiRequest<void>('/v1/auth/logout', { authRequest: true, method: 'POST' });
      clearIdentity();
      await refreshCsrf();
    } catch {
      if (mounted.current) clearIdentity('Unable to confirm server logout. Reload and retry logout before sharing this browser.');
    } finally {
      transition.current = false;
      if (mounted.current) setTransitioning(remoteTransition.current);
      notifyTransition('complete');
    }
  };
  return <AuthContext.Provider value={{ user, initializing, revision, notice, transitioning, login, logout }}>{children}</AuthContext.Provider>;
}
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error('useAuth requires AuthProvider');
  return context;
}
