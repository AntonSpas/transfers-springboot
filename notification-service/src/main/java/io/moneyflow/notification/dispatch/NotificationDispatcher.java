package io.moneyflow.notification.dispatch;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.moneyflow.notification.notification.Notification;
import io.moneyflow.notification.notification.NotificationRepository;
import io.moneyflow.notification.notification.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Отправляет уведомления через {@link NotificationChannel}, не держа транзакцию БД на время отправки.
 *
 * <p>Один проход {@link #runOnce()}:
 * <ol>
 *   <li>Захват, короткая транзакция: партия {@code NEW} и {@code SENDING} с истёкшей арендой блокируется
 *   ({@code SKIP LOCKED}), получает статус {@code SENDING} и аренду {@code sending_until = now + lease}.</li>
 *   <li>Отправка по одному вне транзакции: соединение с БД не занято.</li>
 *   <li>Итог каждого в своей короткой транзакции: {@code SENT} (+ {@code sent_at}) либо, при исключении канала,
 *   {@code attempts + 1} и {@code last_error}: пока попыток меньше {@code max-attempts}, уведомление возвращается
 *   в {@code NEW} с паузой ({@code next_attempt_at}), после последней становится {@code FAILED}.</li>
 * </ol>
 *
 * <p>Доставка at-least-once. Если проход не записал итог (падение, сбой БД), строка остаётся в {@code SENDING} и
 * после аренды отправляется снова. Если запись итога сама падает, проход прерывается: продолжать при недоступной БД
 * значило бы множить повторные отправки.
 *
 * <p>Аренда выдаётся сразу всей партии. Когда она истекла, а часть партии ещё не отправлена, проход
 * останавливается: эти строки уже вправе взять другой экземпляр. Неудача записывается, только если захват всё ещё
 * свой; успех записывается всегда, потому что доставка уже произошла.
 *
 * <p>Итоги считает {@code notification.dispatch.result} с тегом {@code status} = {@code sent} | {@code failed}
 * ({@code failed} только окончательные: неудачи, за которыми будет повтор, в него не входят). Счётчик растёт после
 * коммита записанного итога.
 */
@Component
public class NotificationDispatcher {

    /**
     * Итог прохода.
     *
     * @param sent      сколько уведомлений переведено в {@code SENT}
     * @param failed    сколько переведено в {@code FAILED}
     * @param reclaimed сколько в захваченной партии было строк {@code SENDING} с истёкшей арендой
     * @param retried   сколько после неудачи возвращено в {@code NEW} на повтор
     */
    public record Report(int sent, int failed, int reclaimed, int retried) {

        public int total() {
            return sent + failed + retried;
        }
    }

    /** Захваченная партия; {@code sendingUntil} отличает этот захват от следующих. */
    private record Claim(List<Notification> notifications, Instant sendingUntil, int reclaimed) {
    }

    static final String RESULT_METRIC = "notification.dispatch.result";

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationRepository repository;
    private final NotificationChannel channel;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DispatchProperties properties;
    private final Counter sentCounter;
    private final Counter failedCounter;

    public NotificationDispatcher(NotificationRepository repository, NotificationChannel channel,
                                  PlatformTransactionManager transactionManager, Clock clock,
                                  DispatchProperties properties, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.channel = channel;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
        // регистрируются сразу, чтобы в метриках был ноль, а не отсутствующий ряд
        this.sentCounter = resultCounter(meterRegistry, "sent");
        this.failedCounter = resultCounter(meterRegistry, "failed");
    }

    private static Counter resultCounter(MeterRegistry meterRegistry, String status) {
        return Counter.builder(RESULT_METRIC)
                .description("Итоги отправки уведомлений")
                .tag("status", status)
                .register(meterRegistry);
    }

    /** Вызывается вне транзакции: каждый шаг открывает свою. */
    public Report runOnce() {
        Claim claim = tx.execute(status -> claimBatch());
        if (claim.reclaimed() > 0) {
            log.warn("Повторно захвачено уведомлений с истёкшей арендой: {}", claim.reclaimed());
        }
        int sent = 0;
        int failed = 0;
        int retried = 0;
        int processed = 0;
        for (Notification notification : claim.notifications()) {
            if (!now().isBefore(claim.sendingUntil())) {
                log.warn("Аренда истекла до конца прохода, не отправлено уведомлений: {} (их возьмёт следующий захват)",
                        claim.notifications().size() - processed);
                break;
            }
            processed++;
            Optional<String> error = deliver(notification);
            if (error.isEmpty()) {
                if (recordSent(notification.getId())) {
                    sentCounter.increment();
                    sent++;
                }
            } else {
                Optional<NotificationStatus> recorded =
                        recordFailure(notification.getId(), claim.sendingUntil(), error.get());
                if (recorded.equals(Optional.of(NotificationStatus.FAILED))) {
                    failedCounter.increment();
                    failed++;
                } else if (recorded.isPresent()) {
                    retried++;
                }
            }
        }
        Report report = new Report(sent, failed, claim.reclaimed(), retried);
        if (report.total() > 0) {
            log.info("Отправка уведомлений: {}", report);
        }
        return report;
    }

    private Claim claimBatch() {
        Instant now = now();
        Instant sendingUntil = now.plus(properties.lease()).truncatedTo(ChronoUnit.MICROS);
        List<Notification> batch = repository.lockDispatchable(now, properties.batchSize());
        int reclaimed = (int) batch.stream()
                .filter(notification -> notification.getStatus() == NotificationStatus.SENDING)
                .count();
        // изменения сохраняются при коммите транзакции (сущности управляются контекстом)
        batch.forEach(notification -> notification.claim(sendingUntil));
        return new Claim(batch, sendingUntil, reclaimed);
    }

    /** @return пусто при успехе, иначе описание ошибки канала */
    private Optional<String> deliver(Notification notification) {
        try {
            channel.send(notification);
            return Optional.empty();
        } catch (RuntimeException e) {
            log.warn("Уведомление {} не отправлено: {}", notification.getId(), e.toString());
            return Optional.of(e.toString());
        }
    }

    private boolean recordSent(UUID id) {
        return tx.execute(status -> {
            Optional<Notification> current = repository.lockById(id)
                    .filter(notification -> notification.getStatus() != NotificationStatus.SENT);
            current.ifPresent(notification -> notification.markSent(now()));
            return current.isPresent();
        });
    }

    /** @return новый статус ({@code NEW} или {@code FAILED}); пусто, если захват уже не наш и ничего не изменено */
    private Optional<NotificationStatus> recordFailure(UUID id, Instant sendingUntil, String error) {
        Optional<NotificationStatus> recorded = tx.execute(status -> {
            Optional<Notification> own = repository.lockById(id)
                    .filter(notification -> notification.isClaimedUntil(sendingUntil));
            own.ifPresent(notification -> applyFailure(notification, error));
            return own.map(Notification::getStatus);
        });
        if (recorded.isEmpty()) {
            log.warn("Неудача отправки уведомления {} не записана: его уже захватил или обработал другой проход", id);
        }
        return recorded;
    }

    private void applyFailure(Notification notification, String error) {
        DispatchProperties.Retry retry = properties.retry();
        int attempt = notification.getAttempts() + 1;
        if (attempt < retry.maxAttempts()) {
            Instant nextAttemptAt = now().plus(retry.delayAfter(attempt)).truncatedTo(ChronoUnit.MICROS);
            notification.scheduleRetry(error, nextAttemptAt);
            log.info("Уведомление {}: попытка {} из {} неудачна, следующая не раньше {}",
                    notification.getId(), attempt, retry.maxAttempts(), nextAttemptAt);
        } else {
            notification.markFailed(error);
            log.warn("Уведомление {}: попытки кончились ({}), статус FAILED", notification.getId(), attempt);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
