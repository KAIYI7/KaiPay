export type PaymentStatus =
  | 'CREATED'
  | 'PROCESSING'
  | 'AUTHORIZED'
  | 'CAPTURED'
  | 'FAILED'
  | 'DECLINED'
  | 'REFUND_PENDING'
  | 'PARTIALLY_REFUNDED'
  | 'REFUNDED';

export interface Payment {
  id: string;
  merchantId: string;
  customerId: string;
  paymentMethodId?: string | null;
  amountCents: number;
  currency: string;
  status: PaymentStatus;
  idempotencyKey: string;
  gatewayReference?: string | null;
  failureCode?: string | null;
  failureMessage?: string | null;
  metadata: Record<string, unknown>;
  version: number;
  createdAt: string;
  updatedAt: string;
}

export interface CreatePaymentRequest {
  amountCents: number;
  currency: string;
  customerId: string;
  paymentMethodId?: string | null;
  metadata?: Record<string, unknown>;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
  first: boolean;
  last: boolean;
  empty: boolean;
}
