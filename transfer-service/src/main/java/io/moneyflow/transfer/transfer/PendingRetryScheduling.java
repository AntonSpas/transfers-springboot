package io.moneyflow.transfer.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Включает планировщик и регистрирует периодический проход {@link PendingTransferRetrier} только при
 * {@code app.pending-retry.enabled=true}. Пауза считается от конца предыдущего запуска, поэтому запуски
 * внутри одного экземпляра не накладываются. Интервал задаётся {@link java.time.Duration}, а не строкой
 * в аннотации: так формат значения проверяет привязка свойств.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.pending-retry", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PendingRetryScheduling implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(PendingRetryScheduling.class);

    private final PendingTransferRetrier retrier;
    private final PendingRetryProperties properties;

    public PendingRetryScheduling(PendingTransferRetrier retrier, PendingRetryProperties properties) {
        this.retrier = retrier;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new IntervalTask(this::runSafely, properties.interval(), properties.interval()));
    }

    /** Исключение не должно выходить наружу: следующий запуск всё равно произойдёт, а причина попадёт в лог. */
    void runSafely() {
        try {
            retrier.runOnce();
        } catch (RuntimeException e) {
            log.error("Pending transfers retry run failed", e);
        }
    }
}
