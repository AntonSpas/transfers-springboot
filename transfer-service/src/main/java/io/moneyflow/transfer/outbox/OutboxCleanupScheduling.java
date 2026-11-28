package io.moneyflow.transfer.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Включает планировщик и регистрирует периодический проход {@link OutboxCleaner} только при
 * {@code app.outbox.cleanup.enabled=true}. Пауза считается от конца предыдущего прохода.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.outbox.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxCleanupScheduling implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupScheduling.class);

    private final OutboxCleaner cleaner;
    private final OutboxCleanupProperties properties;

    public OutboxCleanupScheduling(OutboxCleaner cleaner, OutboxCleanupProperties properties) {
        this.cleaner = cleaner;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new IntervalTask(this::runSafely, properties.interval(), properties.interval()));
    }

    /** Исключение не должно выходить наружу: следующий проход всё равно произойдёт, а причина попадёт в лог. */
    void runSafely() {
        try {
            cleaner.runOnce();
        } catch (RuntimeException e) {
            log.error("Outbox cleanup run failed", e);
        }
    }
}
