import { apiRequest } from './fetchClient';
import { PageResponse } from '../models/payment';
import { ReconciliationFilter, ReconciliationObservation, ReconciliationRun } from '../models/reconciliation';

function route(merchantId: string, admin: boolean, suffix: string, params = new URLSearchParams()) {
  if (admin) params.set('merchantId', merchantId);
  const query = params.toString();
  return `/v1/${admin ? 'admin/' : ''}reconciliation/runs${suffix}${query ? `?${query}` : ''}`;
}
export const reconciliationApi = {
  runs(merchantId: string, admin: boolean, page: number, signal?: AbortSignal) {
    return apiRequest<PageResponse<ReconciliationRun>>(route(merchantId, admin, '', new URLSearchParams({ page: String(page), size: '20' })),
      { merchantId: admin ? undefined : merchantId, signal });
  },
  detail(merchantId: string, admin: boolean, runId: string, signal?: AbortSignal) {
    return apiRequest<ReconciliationRun>(route(merchantId, admin, `/${encodeURIComponent(runId)}`), { merchantId: admin ? undefined : merchantId, signal });
  },
  observations(merchantId: string, admin: boolean, runId: string, page: number, filter: ReconciliationFilter, signal?: AbortSignal) {
    const params = new URLSearchParams({ page: String(page), size: '20' });
    if (filter) params.set('primaryClass', filter);
    return apiRequest<PageResponse<ReconciliationObservation>>(route(merchantId, admin, `/${encodeURIComponent(runId)}/observations`, params),
      { merchantId: admin ? undefined : merchantId, signal });
  },
};
