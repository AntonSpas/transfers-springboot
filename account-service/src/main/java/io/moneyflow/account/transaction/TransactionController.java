package io.moneyflow.account.transaction;

import io.moneyflow.account.common.PageResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/accounts/{accountId}/transactions")
public class TransactionController {

    private static final int MAX_PAGE_SIZE = 100;

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    /**
     * Pageable намеренно не принимаем напрямую: через него клиент мог бы передать sort,
     * который наложился бы на фиксированный порядок «новые сверху». Параметры page и size
     * приводятся к допустимым границам, а не отклоняются: page >= 0, 1 <= size <= 100.
     */
    @GetMapping
    public PageResponse<TransactionResponse> history(@PathVariable UUID accountId,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return PageResponse.from(service.getHistory(accountId, pageRequest), TransactionResponse::from);
    }
}
