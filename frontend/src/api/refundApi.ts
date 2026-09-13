import { apiRequest } from './fetchClient';
import { Refund, CreateRefundPayload } from '../models/refund';
import { generateIdempotencyKey } from '../utils/formatters';

export const refundApi = {
  /**
   * Issue a full or partial refund for a captured payment
   */
  async createRefund(
    paymentId: string,
    payload: CreateRefundPayload,
    idempotencyKey?: string
  ): Promise<Refund> {
    const key = idempotencyKey || generateIdempotencyKey();
    return apiRequest<Refund>(`/v1/payments/${paymentId}/refunds`, {
      method: 'POST',
      idempotencyKey: key,
      body: JSON.stringify(payload),
    });
  },

  /**
   * Fetch all refunds recorded for a given payment ID
   */
  async listPaymentRefunds(paymentId: string): Promise<Refund[]> {
    return apiRequest<Refund[]>(`/v1/payments/${paymentId}/refunds`, {
      method: 'GET',
    });
  },
};
