package com.lky.kaipay.common.api;

import com.lky.kaipay.common.exception.InvalidRequestException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/** Bound database result materialization at the HTTP boundary. */
public final class BoundedPageRequest {
    private BoundedPageRequest() {}
    public static PageRequest of(int page, int size) { return of(page, size, Sort.unsorted()); }
    public static PageRequest of(int page, int size, Sort sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new InvalidRequestException("Page must be non-negative and size must be between 1 and 100");
        }
        return PageRequest.of(page, size, sort);
    }
}
