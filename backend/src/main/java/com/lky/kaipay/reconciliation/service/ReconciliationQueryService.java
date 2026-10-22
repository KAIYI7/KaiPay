package com.lky.kaipay.reconciliation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lky.kaipay.common.api.BoundedPageRequest;
import com.lky.kaipay.common.exception.EntityNotFoundException;
import com.lky.kaipay.common.exception.InvalidRequestException;
import com.lky.kaipay.reconciliation.api.ReconciliationViews.*;
import com.lky.kaipay.security.ResourceAccess;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class ReconciliationQueryService {
    public static final Set<String> CLASSES = Set.of("OWNERSHIP_CONFLICT", "EVIDENCE_INVALID", "REQUEST_MISMATCH",
            "AUTHORIZATION_MISMATCH", "INTENT_STATE_CONFLICT", "PENDING_FINALIZATION", "FINALIZATION_OUTSTANDING",
            "UNRESOLVED", "INTENT_PENDING", "QUEUED", "EVIDENCE_UNAVAILABLE", "AMBIGUOUS_PROCESSING", "MATCHED");
    private static final Set<String> REASONS = Set.of("TIMING_UNCERTAIN", "HISTORICAL_NON_MYR", "RECOVERY_BLOCKED_NON_MYR",
            "MERCHANT_BINDING_DIFFERS", "UNSUPPORTED_OR_INVALID_EVIDENCE", "AMOUNT_DIFFERS", "CURRENCY_DIFFERS",
            "INTENT_WITH_CREATED_PAYMENT", "AGED_PENDING", "STATE_DIFFERS", "REFERENCE_DIFFERS", "CODE_DIFFERS", "MESSAGE_DIFFERS");
    private static final String RUN_COLUMNS = "id,merchant_id,scope,ruleset,schema_version,build_revision,from_inclusive,"
            + "cutoff_exclusive,grace_seconds,payment_limit,execution_limit_seconds,snapshot_observed_at,completed_at,cohort_count,counts";
    private static final Set<String> PAYMENT_FIELDS = Set.of("paymentAmountCents", "paymentCurrency", "paymentStatus",
            "paymentVersion", "paymentCreatedAt", "paymentUpdatedAt", "operationPresent", "merchantMatches",
            "eventIds", "markerEventIds", "contextIncomplete");
    private static final Set<String> BOOLEAN_FIELDS = Set.of("operationPresent", "merchantMatches", "referencePresent",
            "failureCodePresent", "failureMessagePresent", "referenceMatches", "codeMatches", "messageMatches", "contextIncomplete");
    private static final Set<String> LONG_FIELDS = Set.of("paymentAmountCents", "paymentVersion", "contractVersion",
            "operationAmountCents", "evaluationCount");
    private static final Set<String> TIME_FIELDS = Set.of("paymentCreatedAt", "paymentUpdatedAt", "operationCreatedAt", "operationUpdatedAt", "resultAt");
    private static final Set<String> OPERATION_FIELDS = Set.of("namespace", "contractVersion", "operationAmountCents", "operationCurrency",
            "disposition", "evaluationCount", "operationCreatedAt", "operationUpdatedAt", "resultAt", "referencePresent",
            "failureCodePresent", "failureMessagePresent", "referenceMatches", "codeMatches", "messageMatches", "safeCode");
    private static final Map<String, String> EXPLANATIONS = Map.ofEntries(
            Map.entry("OWNERSHIP_CONFLICT", "Stored authorization evidence is not bound to this payment's merchant. Review protected records."),
            Map.entry("EVIDENCE_INVALID", "Stored evidence does not satisfy the supported authorization contract. No financial loss is inferred."),
            Map.entry("REQUEST_MISMATCH", "The payment amount or currency differs from its stored simulator authorization request."),
            Map.entry("AUTHORIZATION_MISMATCH", "The current payment authorization state or result fields differ from the durable simulator outcome."),
            Map.entry("INTENT_STATE_CONFLICT", "An authorization operation exists while the payment remains CREATED."),
            Map.entry("PENDING_FINALIZATION", "A definitive simulator result exists; the payment remains PROCESSING in the permitted finalization window."),
            Map.entry("FINALIZATION_OUTSTANDING", "A definitive simulator result exists; local finalization was not observed. Retained normal redelivery may finish it."),
            Map.entry("UNRESOLVED", "No definitive simulator outcome persisted. Timeout or retry exhaustion cannot establish acceptance or rejection."),
            Map.entry("INTENT_PENDING", "An authorization intent exists but no simulator evaluation was observed."),
            Map.entry("QUEUED", "No authorization attempt evidence was observed for this CREATED payment."),
            Map.entry("EVIDENCE_UNAVAILABLE", "Independent authorization evidence is unavailable. Historical compatibility does not establish missing provider evidence."),
            Map.entry("AMBIGUOUS_PROCESSING", "The PROCESSING payment has no durable authorization operation. Its outcome cannot be determined."),
            Map.entry("MATCHED", "The current payment and durable simulator outcome agree for authorization only. Capture/refund accounting was not assessed."));
    private final ResourceAccess access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ReconciliationQueryService(ResourceAccess access, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.access = access; this.jdbc = jdbc; this.mapper = mapper;
    }
    public static void parameters(Map<String, String> parameters, boolean admin, boolean observations) {
        var allowed = new HashSet<>(Set.of("page", "size"));
        if (admin) allowed.add("merchantId");
        if (observations) { allowed.add("primaryClass"); allowed.add("paymentId"); }
        if (!allowed.containsAll(parameters.keySet())) throw new InvalidRequestException("Unsupported reconciliation query parameter");
    }
    private void authorize(UUID merchant, boolean admin) {
        if (admin) { access.admin(); if (merchant == null) throw new InvalidRequestException("An explicit merchant is required"); }
        else access.merchant(merchant);
    }
    private org.springframework.data.domain.Pageable page(int page, int size) {
        if (page > 100) throw new InvalidRequestException("Page must not exceed 100");
        return BoundedPageRequest.of(page, size);
    }
    public Page<RunView> runs(UUID merchant, int page, int size, boolean admin) {
        authorize(merchant, admin); var pageable = page(page, size);
        Long total = jdbc.queryForObject("SELECT count(*) FROM reconciliation_runs WHERE merchant_id=?", Long.class, merchant);
        var rows = jdbc.query("SELECT " + RUN_COLUMNS + " FROM reconciliation_runs WHERE merchant_id=? "
                + "ORDER BY snapshot_observed_at DESC,id DESC LIMIT ? OFFSET ?", this::run, merchant, size, pageable.getOffset());
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }
    public RunView detail(UUID merchant, UUID runId, boolean admin) {
        authorize(merchant, admin); return find(merchant, runId);
    }
    private RunView find(UUID merchant, UUID runId) {
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM reconciliation_runs WHERE id=? AND merchant_id=?", this::run,
                runId, merchant).stream().findFirst().orElseThrow(() -> new EntityNotFoundException("Reconciliation run not found"));
    }
    public Page<ObservationView> observations(UUID merchant, UUID runId, int page, int size,
                                              String primaryClass, UUID paymentId, boolean admin) {
        authorize(merchant, admin); var pageable = page(page, size); find(merchant, runId);
        if (primaryClass != null && !CLASSES.contains(primaryClass) && !"NON_MATCHED".equals(primaryClass))
            throw new InvalidRequestException("Unsupported reconciliation class");
        String filter = " WHERE o.run_id=? AND o.merchant_id=? AND r.id=o.run_id AND r.merchant_id=o.merchant_id";
        var args = new ArrayList<Object>(); args.add(runId); args.add(merchant);
        if ("NON_MATCHED".equals(primaryClass)) filter += " AND o.primary_class<>'MATCHED'";
        else if (primaryClass != null) { filter += " AND o.primary_class=?"; args.add(primaryClass); }
        if (paymentId != null) { filter += " AND o.payment_id=?"; args.add(paymentId); }
        String from = " FROM reconciliation_observations o JOIN reconciliation_runs r ON r.id=o.run_id AND r.merchant_id=o.merchant_id";
        Long total = jdbc.queryForObject("SELECT count(*)" + from + filter, Long.class, args.toArray());
        args.add(size); args.add(pageable.getOffset());
        var rows = jdbc.query("SELECT o.payment_id,o.primary_class,o.severity,o.reason_codes,o.evidence" + from + filter
                + " ORDER BY o.payment_id ASC LIMIT ? OFFSET ?", this::observation, args.toArray());
        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }
    private RunView run(ResultSet rs, int row) throws SQLException {
        var counts = new TreeMap<String, Integer>(); JsonNode data = json(rs.getString("counts"));
        for (String name : CLASSES) { int n = data.path(name).asInt(0); counts.put(name, Math.max(0, Math.min(1000, n))); }
        return new RunView(rs.getObject("id", UUID.class), rs.getObject("merchant_id", UUID.class),
                "AUTHORIZATION_V1", "AUTHORIZATION_V1.0", rs.getInt("schema_version"), safeBuild(rs.getString("build_revision")),
                instant(rs, "from_inclusive"), instant(rs, "cutoff_exclusive"), rs.getInt("grace_seconds"), rs.getInt("payment_limit"),
                rs.getInt("execution_limit_seconds"), instant(rs, "snapshot_observed_at"), instant(rs, "completed_at"),
                rs.getInt("cohort_count"), counts, "COMPLETE");
    }
    private ObservationView observation(ResultSet rs, int row) throws SQLException {
        String rawClass = rs.getString("primary_class"), type = CLASSES.contains(rawClass) ? rawClass : "EVIDENCE_INVALID";
        String severity = rs.getString("severity"); if (!Set.of("INFO", "WARNING", "ERROR").contains(severity)) severity = "WARNING";
        var reasons = new TreeSet<String>(); JsonNode rawReasons = json(rs.getString("reason_codes"));
        if (rawReasons.isArray()) for (JsonNode reason : rawReasons) if (reason.isTextual() && REASONS.contains(reason.asText()) && reasons.size() < 16) reasons.add(reason.asText());
        JsonNode source = json(rs.getString("evidence")); ObjectNode evidence = mapper.createObjectNode();
        var allowed = new HashSet<>(PAYMENT_FIELDS);
        if (source.path("merchantMatches").asBoolean(false) && source.path("operationPresent").asBoolean(false)) allowed.addAll(OPERATION_FIELDS);
        for (String name : allowed) {
            JsonNode value = source.get(name); if (value == null || value.isNull()) continue;
            if (BOOLEAN_FIELDS.contains(name) && value.isBoolean()) evidence.set(name, value);
            else if (LONG_FIELDS.contains(name) && value.isIntegralNumber() && value.canConvertToLong()) evidence.set(name, value);
            else if (TIME_FIELDS.contains(name) && value.isTextual()) { try { evidence.put(name, Instant.parse(value.asText()).toString()); } catch (RuntimeException ignored) { /* Omit invalid diagnostic timestamps. */ } }
            else if ((name.equals("eventIds") || name.equals("markerEventIds")) && value.isArray()) {
                var array = evidence.putArray(name); for (JsonNode id : value) { if (array.size() == 4) break; try { if (id.isTextual()) array.add(UUID.fromString(id.asText()).toString()); } catch (RuntimeException ignored) { /* No arbitrary strings cross the boundary. */ } }
            } else if (value.isTextual() && safeText(name, value.asText())) evidence.set(name, value);
        }
        return new ObservationView(rs.getObject("payment_id", UUID.class), type, severity, List.copyOf(reasons), evidence,
                EXPLANATIONS.get(type) + " Reconciliation made no financial changes.");
    }
    private static boolean safeText(String key, String value) {
        return switch (key) {
            case "paymentCurrency", "operationCurrency" -> value.matches("[A-Z]{3}");
            case "paymentStatus" -> Set.of("CREATED", "PROCESSING", "AUTHORIZED", "CAPTURED", "FAILED", "DECLINED", "REFUND_PENDING", "PARTIALLY_REFUNDED", "REFUNDED", "UNKNOWN").contains(value);
            case "disposition" -> Set.of("UNRESOLVED", "APPROVED", "DECLINED", "REJECTED_NO_ACCEPTANCE", "UNKNOWN").contains(value);
            case "namespace" -> Set.of("kaipay-sim-auth-v1", "UNKNOWN").contains(value);
            case "safeCode" -> Set.of("INSUFFICIENT_FUNDS", "SIMULATED_NO_ACCEPTANCE", "OTHER").contains(value);
            default -> false;
        };
    }
    private JsonNode json(String value) { try { return mapper.readTree(value); } catch (Exception e) { return mapper.createObjectNode(); } }
    private static String safeBuild(String value) { return value != null && value.matches("[A-Za-z0-9._-]{1,100}") ? value : "UNKNOWN"; }
    private static Instant instant(ResultSet rs, String name) throws SQLException { return rs.getTimestamp(name).toInstant(); }
}
