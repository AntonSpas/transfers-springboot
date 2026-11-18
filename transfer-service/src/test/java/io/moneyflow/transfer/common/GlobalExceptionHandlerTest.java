package io.moneyflow.transfer.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Быстрый тест без Spring-контекста и БД: обработчик подключён к тестовому контроллеру через standalone MockMvc.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void apiExceptionKeepsItsStatusAndCode() throws Exception {
        mvc.perform(get("/probe/api-error"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT"))
                .andExpect(jsonPath("$.message").value("Accounts must differ"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.violations").doesNotExist());
    }

    @Test
    void lockFailureIsServiceUnavailableWithRetryAfter() throws Exception {
        mvc.perform(get("/probe/busy"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("RESOURCE_BUSY"));
    }

    @Test
    void optimisticLockFailureHasItsOwnCode() throws Exception {
        mvc.perform(get("/probe/optimistic"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"))
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void unexpectedErrorIsInternalErrorWithoutDetails() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Internal server error"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret"))));
    }

    @Test
    void validationFailureListsViolations() throws Exception {
        mvc.perform(post("/probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("name"))
                .andExpect(jsonPath("$.violations[0].message").exists());
    }

    @Test
    void malformedJsonStaysBadRequest() throws Exception {
        mvc.perform(post("/probe/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void missingRequiredHeaderStaysBadRequest() throws Exception {
        mvc.perform(get("/probe/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void unsupportedMethodStaysClientError() throws Exception {
        mvc.perform(post("/probe/boom"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/api-error")
        String apiError() {
            throw new ProbeApiException();
        }

        @GetMapping("/probe/busy")
        String busy() {
            throw new CannotAcquireLockException("lock timeout");
        }

        @GetMapping("/probe/optimistic")
        String optimistic() {
            throw new OptimisticLockingFailureException("version conflict");
        }

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/probe/header")
        String header(@RequestHeader("Idempotency-Key") String key) {
            return key;
        }

        @PostMapping("/probe/validate")
        String validate(@Valid @RequestBody ProbeBody body) {
            return body.name();
        }
    }

    record ProbeBody(@NotBlank String name) {
    }

    static class ProbeApiException extends ApiException {
        ProbeApiException() {
            super(HttpStatus.UNPROCESSABLE_ENTITY, "SAME_ACCOUNT", "Accounts must differ");
        }
    }
}
