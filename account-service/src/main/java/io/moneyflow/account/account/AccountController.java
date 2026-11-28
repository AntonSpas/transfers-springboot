package io.moneyflow.account.account;

import io.moneyflow.account.auth.CurrentUser;
import io.moneyflow.account.transaction.TransactionResponse;
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
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    /** Владелец нового счёта — пользователь из токена. */
    @PostMapping
    public ResponseEntity<AccountResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                  @Valid @RequestBody CreateAccountRequest request) {
        Account account = service.createAccount(CurrentUser.id(jwt), request.currency());
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(account.getId()).toUri();
        return ResponseEntity.created(location).body(AccountResponse.from(account));
    }

    /** Счета пользователя из токена, по порядку валют; без пагинации. */
    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return service.getOwnedAccounts(CurrentUser.id(jwt)).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public AccountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return AccountResponse.from(service.getOwnedAccount(id, CurrentUser.id(jwt)));
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse balance(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return BalanceResponse.from(service.getOwnedAccount(id, CurrentUser.id(jwt)));
    }

    /**
     * Заголовок Idempotency-Key обязателен: повтор с тем же ключом (например, после таймаута клиента)
     * не пополнит счёт второй раз. 201 — пополнение выполнено сейчас, 200 — повтор, возвращена сохранённая проводка.
     */
    @PostMapping("/{id}/deposits")
    public ResponseEntity<TransactionResponse> deposit(@AuthenticationPrincipal Jwt jwt,
                                                       @PathVariable UUID id,
                                                       @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                       @Valid @RequestBody DepositRequest request) {
        DepositResult result = service.depositByUser(id, CurrentUser.id(jwt), request.amount(), idempotencyKey);
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(TransactionResponse.from(result.transaction()));
    }
}
