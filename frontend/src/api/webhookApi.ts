import { apiRequest } from './fetchClient';
import { PageResponse } from '../models/payment';
import { WebhookDelivery, WebhookDetail, WebhookEndpoint, WebhookState } from '../models/webhook';

export const webhookApi = {
  endpoint(merchantId: string, signal?: AbortSignal) {
    return apiRequest<WebhookEndpoint>('/v1/webhooks/endpoint', { merchantId, signal });
  },
  deliveries(merchantId: string, page = 0, state?: WebhookState, signal?: AbortSignal) {
    const params = new URLSearchParams({ page: String(page), size: '20' });
    if (state) params.set('state', state);
    return apiRequest<PageResponse<WebhookDelivery>>(`/v1/webhooks/deliveries?${params}`, { merchantId, signal });
  },
  detail(merchantId: string, id: string, signal?: AbortSignal) {
    return apiRequest<WebhookDetail>(`/v1/webhooks/deliveries/${encodeURIComponent(id)}`, { merchantId, signal });
  },
};
