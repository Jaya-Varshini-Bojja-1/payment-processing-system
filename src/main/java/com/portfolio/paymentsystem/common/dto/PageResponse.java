package com.portfolio.paymentsystem.common.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * A stable, explicit JSON shape for paginated responses. Returning Spring
 * Data's {@code Page<T>} directly from a controller works but serializes
 * Hibernate/Spring-internal fields and is not a contract we want to freeze
 * in an OpenAPI schema.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
