package com.lky.kaipay.dlt.api;

import com.lky.kaipay.common.api.ApiResponse;
import com.lky.kaipay.dlt.api.dto.DeadLetterEventResponse;
import com.lky.kaipay.dlt.domain.DeadLetterEvent;
import com.lky.kaipay.dlt.repository.DeadLetterEventRepository;
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

import java.util.UUID;

/**
 * Internal Platform Operational / Debug API for the Dead Letter Topic (DLT) Explorer. Not for external merchant use.
 */
@Slf4j
@RestController
@RequestMapping("/v1/events/dlt")
@RequiredArgsConstructor
public class DltAdminController {

    private final DeadLetterEventRepository deadLetterEventRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<Page<DeadLetterEventResponse>>> listDeadLetterEvents(
            @RequestParam(name = "paymentId", required = false) UUID paymentId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) {
        log.info("Fetching operational dead letter events with paymentId filter: {}, page: {}, size: {}", paymentId, page, size);
        Pageable pageable = PageRequest.of(page, size);
        Page<DeadLetterEvent> events = (paymentId != null)
                ? deadLetterEventRepository.findByPaymentIdOrderByCreatedAtDesc(paymentId, pageable)
                : deadLetterEventRepository.findAllByOrderByCreatedAtDesc(pageable);
        Page<DeadLetterEventResponse> response = events.map(DeadLetterEventResponse::fromEntity);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
