package io.moneyflow.notification.dispatch;

import io.moneyflow.notification.notification.Notification;
import io.moneyflow.notification.notification.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Отправляет уведомления в статусе {@code NEW} через {@link NotificationChannel}.
 *
 * <p>Один проход {@link #runOnce()}: одна транзакция, в ней {@code lockNew} (строки блокируются с
 * {@code SKIP LOCKED}, поэтому несколько экземпляров сервиса не берут одну строку дважды), затем для каждой строки
 * {@code send} и статус {@code SENT} (+ {@code sent_at}) либо {@code FAILED} при исключении канала.
 * {@code FAILED} терминальный: повторов нет, сбой одного уведомления не мешает остальным.
 *
 * <p>Осознанное исключение из правила «внешний вызов вне транзакции» (то же, что в outbox, решение 40): отправка
 * идёт под блокировкой строк. Для заглушки с записью в лог риск нулевой; для реального канала решение надо
 * пересмотреть (долгий вызов держит транзакцию, а откат после успешной отправки приведёт к повторной отправке).
 */
@Component
public class NotificationDispatcher {

    /** Итог прохода. */
    public record Report(int sent, int failed) {

        public int total() {
            return sent + failed;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationRepository repository;
    private final NotificationChannel channel;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final DispatchProperties properties;

    public NotificationDispatcher(NotificationRepository repository, NotificationChannel channel,
                                  PlatformTransactionManager transactionManager, Clock clock,
                                  DispatchProperties properties) {
        this.repository = repository;
        this.channel = channel;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
    }

    public Report runOnce() {
        Report report = tx.execute(status -> dispatchBatch());
        if (report.total() > 0) {
            log.info("Отправка уведомлений: {}", report);
        }
        return report;
    }

    private Report dispatchBatch() {
        List<Notification> batch = repository.lockNew(properties.batchSize());
        int sent = 0;
        int failed = 0;
        for (Notification notification : batch) {
            if (deliver(notification)) {
                notification.markSent(now());
                sent++;
            } else {
                notification.markFailed();
                failed++;
            }
        }
        // изменения статусов сохраняются при коммите транзакции (сущности управляются контекстом)
        return new Report(sent, failed);
    }

    private boolean deliver(Notification notification) {
        try {
            channel.send(notification);
            return true;
        } catch (RuntimeException e) {
            log.warn("Уведомление {} не отправлено, статус FAILED (повторов нет): {}", notification.getId(), e.toString());
            return false;
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
