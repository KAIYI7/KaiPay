package com.lky.kaipay.reconciliation.core;

import java.time.Instant;
import java.util.*;

/** Detects authorization parity only; never calls a financial service. */
final class AuthorizationClassifier {
    private static final Set<String> APPROVED_STATES = Set.of("AUTHORIZED","CAPTURED","REFUND_PENDING","PARTIALLY_REFUNDED","REFUNDED");
    private static final Set<String> STATES = Set.of("CREATED","PROCESSING","AUTHORIZED","CAPTURED","REFUND_PENDING","PARTIALLY_REFUNDED","REFUNDED","DECLINED","FAILED");
    AuthorizationObservation classify(AuthorizationSource s, Instant observedAt) {
        var reasons = new TreeSet<String>();
        boolean owned = s.operationPresent && s.merchantMatches;
        boolean timing = future(s.createdAt,observedAt) || future(s.updatedAt,observedAt) || before(s.updatedAt,s.createdAt)
                || owned && (future(s.operationCreatedAt,observedAt) || future(s.operationUpdatedAt,observedAt)
                || future(s.resultAt,observedAt) || before(s.operationUpdatedAt,s.operationCreatedAt) || before(s.resultAt,s.operationCreatedAt));
        if (timing) reasons.add("TIMING_UNCERTAIN");
        if (!"MYR".equals(s.currency) && (!owned || Objects.equals(s.currency,s.operationCurrency))) reasons.add("HISTORICAL_NON_MYR");
        if (owned && !"MYR".equals(s.operationCurrency) && "UNRESOLVED".equals(s.disposition)) reasons.add("RECOVERY_BLOCKED_NON_MYR");
        if (owned && s.status != null && !Set.of("CREATED","PROCESSING").contains(s.status)) {
            if (s.validShape && !stateMatches(s.disposition,s.status)) reasons.add("STATE_DIFFERS");
            if (!s.referenceMatches) reasons.add("REFERENCE_DIFFERS");
            if (!s.codeMatches) reasons.add("CODE_DIFFERS");
            if (!s.messageMatches) reasons.add("MESSAGE_DIFFERS");
        }
        String kind, severity, explanation;
        if (s.operationPresent && !s.merchantMatches) {
            kind="OWNERSHIP_CONFLICT"; severity="ERROR"; reasons.add("MERCHANT_BINDING_DIFFERS");
            explanation="Stored authorization evidence is not bound to this payment's merchant. Review protected records. No financial changes were made.";
        } else if (s.status == null || !STATES.contains(s.status) || s.createdAt == null || s.updatedAt == null
                || owned && (!"kaipay-sim-auth-v1".equals(s.namespace)
                || !Integer.valueOf(1).equals(s.contractVersion) || !s.validShape || s.disposition == null || s.evaluationCount == null
                || s.evaluationCount < 0 || s.operationAmount == null || s.operationAmount <= 0
                || s.operationCurrency == null || !s.operationCurrency.matches("[A-Z]{3}")
                || !Set.of("APPROVED","DECLINED","REJECTED_NO_ACCEPTANCE","UNRESOLVED").contains(s.disposition)
                || s.operationCreatedAt == null || s.operationUpdatedAt == null
                || !"UNRESOLVED".equals(s.disposition) && s.resultAt == null)) {
            kind="EVIDENCE_INVALID"; severity="ERROR"; reasons.add("UNSUPPORTED_OR_INVALID_EVIDENCE");
            explanation="The retained authorization contract or result shape is unsupported or invalid. Financial outcome cannot be inferred. No financial changes were made.";
        } else if (owned && (!Objects.equals(s.operationAmount,s.amount) || !Objects.equals(s.operationCurrency,s.currency))) {
            kind="REQUEST_MISMATCH"; severity="ERROR";
            if (!Objects.equals(s.operationAmount,s.amount)) reasons.add("AMOUNT_DIFFERS");
            if (!Objects.equals(s.operationCurrency,s.currency)) reasons.add("CURRENCY_DIFFERS");
            explanation="The simulator request and payment amount or currency did not match at observation. No financial changes were made.";
        } else if (owned && "CREATED".equals(s.status)) {
            kind="INTENT_STATE_CONFLICT";severity="ERROR";reasons.add("INTENT_WITH_CREATED_PAYMENT");
            explanation="Authorization intent exists while payment remains CREATED; normal preparation commits intent with PROCESSING. No financial changes were made.";
        } else if (!owned) {
            switch (s.status) {
                case "CREATED" -> { kind="QUEUED";severity="INFO";explanation="No durable authorization attempt evidence exists; payment is queued. This does not establish provider failure."; }
                case "PROCESSING" -> { kind="AMBIGUOUS_PROCESSING";severity="WARNING";explanation="PROCESSING has no retained independent simulator operation. Legacy ambiguity or unavailable evidence prevents a financial conclusion."; }
                default -> { kind="EVIDENCE_UNAVAILABLE";severity="WARNING";explanation="Independent simulator evidence is unavailable. Historical final payments may legitimately lack it; internal payment evidence alone cannot prove authorization parity."; }
            }
            if ("QUEUED".equals(kind) && !timing && !s.createdAt.isAfter(observedAt.minusSeconds(300))) { reasons.add("AGED_PENDING");severity="WARNING"; }
        } else if ("PROCESSING".equals(s.status)) {
            if ("UNRESOLVED".equals(s.disposition)) {
                kind=s.evaluationCount == 0 ? "INTENT_PENDING" : "UNRESOLVED";
                severity=s.evaluationCount == 0 ? "INFO" : "WARNING";
                explanation="No definitive simulator outcome is persisted after " + s.evaluationCount + " evaluations. Timeouts and retry exhaustion do not establish acceptance or rejection.";
                if (!timing && !s.operationCreatedAt.isAfter(observedAt.minusSeconds(300))) { reasons.add("AGED_PENDING");severity="WARNING"; }
            } else if (timing || s.resultAt.isAfter(observedAt.minusSeconds(300))) {
                kind="PENDING_FINALIZATION";severity="INFO";
                explanation="A definitive simulator result exists; payment remains PROCESSING in a permitted or timing-uncertain finalization window. No financial changes were made.";
            } else {
                kind="FINALIZATION_OUTSTANDING";severity="WARNING";
                explanation="A definitive simulator result exists; local finalization was not observed. Normal retained redelivery may finish it; reconciliation has taken no action.";
            }
        } else {
            boolean stateMatches = stateMatches(s.disposition,s.status);
            if (!stateMatches) reasons.add("STATE_DIFFERS");
            if (!s.referenceMatches) reasons.add("REFERENCE_DIFFERS");
            if (!s.codeMatches) reasons.add("CODE_DIFFERS");
            if (!s.messageMatches) reasons.add("MESSAGE_DIFFERS");
            boolean parity = stateMatches && s.referenceMatches && s.codeMatches && s.messageMatches;
            kind=parity?"MATCHED":"AUTHORIZATION_MISMATCH";severity=parity?"INFO":"ERROR";
            explanation=parity?"Retained simulator outcome and current payment authorization evidence match. Capture/refund accounting was not assessed."
                    :"Retained simulator outcome and current payment authorization state or reference/failure fields did not match at observation. No financial changes were made.";
        }
        if (timing && "INFO".equals(severity)) severity="WARNING";
        var e=new LinkedHashMap<String,Object>();
        e.put("paymentAmountCents",s.amount); e.put("paymentCurrency",safeCurrency(s.currency));e.put("paymentStatus",s.status!=null&&STATES.contains(s.status)?s.status:"UNKNOWN");
        e.put("paymentVersion",s.version);e.put("paymentCreatedAt",time(s.createdAt));e.put("paymentUpdatedAt",time(s.updatedAt));
        e.put("operationPresent",s.operationPresent);e.put("merchantMatches",s.merchantMatches);
        if (owned) {
            e.put("namespace","kaipay-sim-auth-v1".equals(s.namespace)?s.namespace:"UNKNOWN");e.put("contractVersion",s.contractVersion);
            e.put("operationAmountCents",s.operationAmount);e.put("operationCurrency",safeCurrency(s.operationCurrency));
            e.put("disposition",s.disposition!=null&&Set.of("APPROVED","DECLINED","REJECTED_NO_ACCEPTANCE","UNRESOLVED").contains(s.disposition)?s.disposition:"UNKNOWN");
            e.put("evaluationCount",s.evaluationCount);e.put("operationCreatedAt",time(s.operationCreatedAt));e.put("operationUpdatedAt",time(s.operationUpdatedAt));e.put("resultAt",time(s.resultAt));
            e.put("referencePresent",s.referencePresent);e.put("failureCodePresent",s.failureCodePresent);e.put("failureMessagePresent",s.failureMessagePresent);
            e.put("referenceMatches",s.referenceMatches);e.put("codeMatches",s.codeMatches);e.put("messageMatches",s.messageMatches);e.put("safeCode",s.safeCode);
        }
        e.put("eventIds",s.eventIds.stream().map(UUID::toString).toList());e.put("markerEventIds",s.markerEventIds.stream().map(UUID::toString).toList());e.put("contextIncomplete",s.contextIncomplete);
        return new AuthorizationObservation(s.paymentId,kind,severity,new ArrayList<>(reasons),e,explanation);
    }
    private static boolean future(Instant value,Instant observed){return value!=null && value.isAfter(observed);}
    private static boolean stateMatches(String disposition,String status){
        if(disposition==null||status==null)return false;
        return switch(disposition){
            case "APPROVED" -> APPROVED_STATES.contains(status);
            case "DECLINED" -> "DECLINED".equals(status);
            case "REJECTED_NO_ACCEPTANCE" -> "FAILED".equals(status);
            default -> false;
        };
    }
    private static boolean before(Instant a,Instant b){return a!=null&&b!=null&&a.isBefore(b);}
    private static String time(Instant value){return value==null?null:value.toString();}
    private static String safeCurrency(String value){return value!=null && value.matches("[A-Z]{3}")?value:"UNKNOWN";}
}
