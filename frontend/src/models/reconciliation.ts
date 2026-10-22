export const reconciliationClasses = ['OWNERSHIP_CONFLICT', 'EVIDENCE_INVALID', 'REQUEST_MISMATCH', 'AUTHORIZATION_MISMATCH',
  'INTENT_STATE_CONFLICT', 'PENDING_FINALIZATION', 'FINALIZATION_OUTSTANDING', 'UNRESOLVED', 'INTENT_PENDING', 'QUEUED',
  'EVIDENCE_UNAVAILABLE', 'AMBIGUOUS_PROCESSING', 'MATCHED'] as const;
export type ReconciliationClass = typeof reconciliationClasses[number];
export type ReconciliationFilter = ReconciliationClass | 'NON_MATCHED' | '';
export interface ReconciliationRun {
  runId: string; merchantId: string; assessmentScope: string; ruleset: string; schemaVersion: number; buildRevision: string;
  fromInclusive: string; cutoffExclusive: string; graceSeconds: number; paymentLimit: number; executionLimitSeconds: number;
  snapshotObservedAt: string; completedAt: string; cohortCount: number; counts: Record<ReconciliationClass, number>; status: string;
}
export interface ReconciliationEvidence {
  paymentAmountCents?: number; paymentCurrency?: string; paymentStatus?: string; paymentVersion?: number;
  paymentCreatedAt?: string; paymentUpdatedAt?: string; operationPresent?: boolean; merchantMatches?: boolean;
  namespace?: string; contractVersion?: number; operationAmountCents?: number; operationCurrency?: string;
  disposition?: string; evaluationCount?: number; operationCreatedAt?: string; operationUpdatedAt?: string; resultAt?: string;
  referencePresent?: boolean; failureCodePresent?: boolean; failureMessagePresent?: boolean;
  referenceMatches?: boolean; codeMatches?: boolean; messageMatches?: boolean; safeCode?: string;
  eventIds?: string[]; markerEventIds?: string[]; contextIncomplete?: boolean;
}
export interface ReconciliationObservation {
  paymentId: string; primaryClass: ReconciliationClass; severity: 'INFO' | 'WARNING' | 'ERROR';
  reasonCodes: string[]; evidence: ReconciliationEvidence; explanation: string;
}
