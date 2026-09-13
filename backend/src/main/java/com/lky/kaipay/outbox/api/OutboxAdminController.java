package com.lky.kaipay.outbox.api;

import com.lky.kaipay.common.api.ApiResponse;
import com.lky.kaipay.outbox.api.dto.OutboxEventResponse;
import com.lky.kaipay.outbox.domain.PaymentEventOutbox;
import com.lky.kaipay.outbox.domain.PaymentEventOutboxStatus;
import com.lky.kaipay.outbox.repository.PaymentEventOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal Platform Operational / Debug API for the Outbox Visualizer Stream. Not for external merchant use.
 */
@Slf4j
@RestController
@RequestMapping("/v1/events/outbox")
@RequiredArgsConstructor
public class OutboxAdminController {

    private final PaymentEventOutboxRepository outboxRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<OutboxEventResponse>>> listOutboxEvents(
            @RequestParam(name = "status", required = false) PaymentEventOutboxStatus status,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        log.info("Fetching operational outbox events with status filter: {}, page: {}, size: {}", status, page, size);
        Pageable pageable = PageRequest.of(page, size);
        Page<PaymentEventOutbox> events = (status != null)
                ? outboxRepository.findByStatusOrderByCreatedAtDesc(status, pageable)
                : outboxRepository.findAllByOrderByCreatedAtDesc(pageable);
        Page<OutboxEventResponse> response = events.map(OutboxEventResponse::fromEntity);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
