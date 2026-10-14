import { apiRequest } from './fetchClient';
import { Payment, CreatePaymentRequest, PageResponse, PaymentStatus } from '../models/payment';
import { generateIdempotencyKey } from '../utils/formatters';

export interface ListPaymentsParams {
  page?: number;
  size?: number;
  status?: PaymentStatus | '';
  sortBy?: string;
  direction?: 'asc' | 'desc';
}

export const paymentsApi = {
  /**
   * List paginated payments for the active merchant
   */
  async listPayments(params: ListPaymentsParams = {}, merchantId?: string): Promise<PageResponse<Payment>> {
    const query = new URLSearchParams();
    if (params.page !== undefined) query.set('page', params.page.toString());
    if (params.size !== undefined) query.set('size', params.size.toString());
    if (params.status) query.set('status', params.status);
    if (params.sortBy) query.set('sortBy', params.sortBy);
    if (params.direction) query.set('direction', params.direction);

    const queryString = query.toString();
    const endpoint = `/v1/payments${queryString ? `?${queryString}` : ''}`;
    return apiRequest<PageResponse<Payment>>(endpoint, { method: 'GET', merchantId });
  },

  /**
   * Get single payment by ID
   */
  async getPayment(paymentId: string, merchantId?: string): Promise<Payment> {
    return apiRequest<Payment>(`/v1/payments/${paymentId}`, { method: 'GET', merchantId });
  },

  /**
   * Create a new payment with explicit idempotency key
   */
  async createPayment(idempotencyKey: string, payload: CreatePaymentRequest, merchantId?: string): Promise<Payment> {
    return apiRequest<Payment>('/v1/payments', {
      method: 'POST',
      merchantId,
      idempotencyKey,
      body: JSON.stringify(payload),
    });
  },

  /**
   * Capture an authorized payment, moving funds to settled state and writing double-entry ledger entries
   */
  async capturePayment(paymentId: string, idempotencyKey?: string, merchantId?: string): Promise<Payment> {
    const key = idempotencyKey || generateIdempotencyKey();
    return apiRequest<Payment>(`/v1/payments/${paymentId}/capture`, {
      method: 'POST',
      merchantId,
      idempotencyKey: key,
    });
  },
};

export const paymentApi = paymentsApi;
