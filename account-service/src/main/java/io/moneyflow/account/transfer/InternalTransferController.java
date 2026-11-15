package io.moneyflow.account.transfer;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Внутренний API для transfer-service, клиентам напрямую не предназначен.
 * 201 — перевод выполнен сейчас, 200 — повторный вызов с тем же transferId, возвращён сохранённый результат.
 * 403 — fromAccountId не принадлежит пользователю initiatorId.
 */
@RestController
@RequestMapping("/internal/transfers")
public class InternalTransferController {

    private final TransferService service;

    public InternalTransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<InternalTransferResponse> transfer(@Valid @RequestBody InternalTransferRequest request) {
        TransferOutcome outcome = service.transfer(request.transferId(), request.initiatorId(),
                request.fromAccountId(), request.toAccountId(), request.amount(), request.currency());
        HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(InternalTransferResponse.from(outcome, request.currency()));
    }
}
