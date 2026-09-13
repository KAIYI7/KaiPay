export type RefundStatus = 'PENDING' | 'COMPLETED' | 'FAILED';

export interface Refund {
  id: string;
  paymentId: string;
  merchantId: string;
  amountCents: number;
  currency: string;
  status: RefundStatus;
  reason?: string | null;
  idempotencyKey: string;
  createdAt: string;
}

export interface CreateRefundPayload {
  amountCents: number;
  reason?: string;
}
