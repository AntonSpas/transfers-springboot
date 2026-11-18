package io.moneyflow.transfer.transfer;

import io.moneyflow.transfer.common.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Публичное API переводов. Инициатор это {@code sub} токена, в теле запроса его нет. */
@RestController
@RequestMapping("/transfers")
public class TransferController {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> create(
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        requireValidKey(idempotencyKey);

        CreateTransferResult result = service.create(CurrentUser.id(jwt), idempotencyKey, request);

        HttpStatus status = switch (result.status()) {
            case CREATED -> HttpStatus.CREATED;
            case EXISTING -> HttpStatus.OK;
            case ACCEPTED -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(status).body(TransferResponse.from(result.transfer()));
    }

    @GetMapping("/{id}")
    public TransferResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        return TransferResponse.from(service.get(id, CurrentUser.id(jwt)));
    }

    private static void requireValidKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException();
        }
    }
}
