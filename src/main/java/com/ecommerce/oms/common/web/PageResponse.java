package com.ecommerce.oms.common.web;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** The paginated envelope used by every list endpoint (docs/design/03-api-spec.md). */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return from(page.map(mapper));
    }
}
