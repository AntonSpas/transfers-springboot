package io.moneyflow.notification.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Включает планировщик и регистрирует периодический проход {@link NotificationDispatcher} только при
 * {@code app.dispatch.enabled=true}. Пауза считается от конца предыдущего прохода, поэтому проходы внутри одного
 * экземпляра не накладываются. Интервал задаётся {@link java.time.Duration}, а не строкой в аннотации:
 * так формат значения проверяет привязка свойств.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.dispatch", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DispatchScheduling implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(DispatchScheduling.class);

    private final NotificationDispatcher dispatcher;
    private final DispatchProperties properties;

    public DispatchScheduling(NotificationDispatcher dispatcher, DispatchProperties properties) {
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(new IntervalTask(this::runSafely, properties.interval(), properties.interval()));
    }

    /**
     * Исключение не должно выходить наружу: следующий проход всё равно произойдёт, а причина попадёт в лог
     * (сбои канала {@link NotificationDispatcher} обрабатывает сам, сюда доходят в основном сбои базы данных).
     */
    void runSafely() {
        try {
            dispatcher.runOnce();
        } catch (RuntimeException e) {
            log.error("Notification dispatch run failed", e);
        }
    }
}
