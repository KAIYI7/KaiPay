export interface DeadLetterEvent {
  id: string;
  originalTopic: string;
  originalPartition: number;
  originalOffset: number;
  eventId?: string | null;
  paymentId?: string | null;
  exceptionClass: string;
  failureMessage?: string | null;
  retryCount: number;
  payload: string;
  headers: Record<string, unknown>;
  createdAt: string;
}
