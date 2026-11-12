package io.moneyflow.account.account;

import io.moneyflow.account.transaction.TransactionResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = service.createAccount(request.ownerId(), request.currency());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(account.getId()).toUri();
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(service.getAccount(id));
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse balance(@PathVariable UUID id) {
        return BalanceResponse.from(service.getAccount(id));
    }

    /**
     * Заголовок Idempotency-Key обязателен: повтор с тем же ключом (например, после таймаута клиента)
     * не пополнит счёт второй раз. 201 — пополнение выполнено сейчас, 200 — повтор, возвращена сохранённая проводка.
     */
    @PostMapping("/{id}/deposits")
    public ResponseEntity<TransactionResponse> deposit(@PathVariable UUID id,
                                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                       @Valid @RequestBody DepositRequest request) {
        DepositResult result = service.deposit(id, request.amount(), idempotencyKey);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(TransactionResponse.from(result.transaction()));
    }
}
