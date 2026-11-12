package io.moneyflow.account.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Собственный формат страницы: Page из Spring Data наружу не отдаём, его JSON-структура нестабильна. */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
