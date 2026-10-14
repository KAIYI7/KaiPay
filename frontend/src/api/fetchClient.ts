import { ApiResponse, ApiError } from '../models/api';

interface RequestOptions extends RequestInit {
  idempotencyKey?: string;
  merchantId?: string;
  authRequest?: boolean;
}
let generation = 0;
let csrf: { token: string; headerName: string } | null = null;
const pending = new Set<AbortController>();
const unauthorizedListeners = new Set<() => void>();
export class StaleSessionError extends Error {
  constructor() { super('The authenticated session changed.'); }
}
// Fence outstanding responses across identities, including mutations whose server
// execution cannot be undone by an aborted HTTP request.
export function resetSessionTransport(clearCsrf = true) {
  generation++;
  if (clearCsrf) csrf = null;
  pending.forEach((controller) => controller.abort());
  pending.clear();
}
export function setCsrfToken(value: { token: string; headerName: string }) { csrf = value; }
export function onUnauthorized(listener: () => void) {
  unauthorizedListeners.add(listener);
  return () => { unauthorizedListeners.delete(listener); };
}
export async function apiRequest<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
  const { idempotencyKey, merchantId, authRequest = false, headers, signal, ...rest } = options;
  if (!endpoint.startsWith('/v1/')) throw new Error('API requests must be same-origin /v1 routes.');
  const originGeneration = generation;
  const controller = new AbortController();
  pending.add(controller);
  const abort = () => controller.abort();
  signal?.addEventListener('abort', abort, { once: true });
  if (signal?.aborted) controller.abort();
  const requestHeaders = new Headers(headers);
  if (!requestHeaders.has('Content-Type')) requestHeaders.set('Content-Type', 'application/json');
  if (merchantId) requestHeaders.set('X-Merchant-Id', merchantId);
  if (idempotencyKey) requestHeaders.set('Idempotency-Key', idempotencyKey);
  const method = (rest.method || 'GET').toUpperCase();
  try {
    if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      if (!csrf) throw new Error('Refresh the session before submitting this request.');
      requestHeaders.set(csrf.headerName, csrf.token);
    }
    const response = await fetch(endpoint, {
      ...rest, method, credentials: 'same-origin', cache: 'no-store',
      headers: requestHeaders, signal: controller.signal,
    });
    const body = await response.json().catch(() => null);
    if (originGeneration !== generation) throw new StaleSessionError();
    if (!response.ok) {
      const message = response.status === 401 ? 'Please sign in to continue.' :
        response.status === 403 ? 'You do not have permission to perform this operation.' :
          body?.message || `Request failed with status ${response.status}`;
      if (response.status === 401 && !authRequest) unauthorizedListeners.forEach((listener) => listener());
      throw new ApiError({ status: response.status, error: response.statusText || 'HttpError',
        message, path: endpoint, timestamp: new Date().toISOString() });
    }
    return response.status === 204 ? undefined as T : (body as ApiResponse<T>).data;
  } catch (error) {
    if (error instanceof ApiError) throw error;
    if (originGeneration !== generation) throw new StaleSessionError();
    if (error instanceof StaleSessionError) throw error;
    throw new ApiError({ status: 0, error: 'NetworkError', message: (error as Error).message,
      path: endpoint, timestamp: new Date().toISOString() });
  } finally {
    pending.delete(controller);
    signal?.removeEventListener('abort', abort);
  }
}
