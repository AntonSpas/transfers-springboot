package io.moneyflow.notification.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Ручная повторная отправка: возвращает уведомления из {@code FAILED} в {@code NEW}. Саму отправку выполняет
 * диспетчер ближайшим проходом. Строки блокируются на время транзакции.
 */
@Service
public class NotificationRetryService {

    /** Сколько уведомлений можно вернуть в очередь одним вызовом. */
    static final int MAX_LIMIT = 1000;

    private static final Logger log = LoggerFactory.getLogger(NotificationRetryService.class);

    private final NotificationRepository repository;

    public NotificationRetryService(NotificationRepository repository) {
        this.repository = repository;
    }

    /**
     * @return уведомление после возврата в очередь
     * @throws NotificationNotFoundException  записи нет
     * @throws NotificationNotFailedException статус не {@code FAILED}
     */
    @Transactional
    public Notification retry(UUID id) {
        Notification notification = repository.lockById(id).orElseThrow(() -> new NotificationNotFoundException(id));
        if (notification.getStatus() != NotificationStatus.FAILED) {
            throw new NotificationNotFailedException(id, notification.getStatus());
        }
        notification.requeue();
        log.info("Уведомление {} возвращено в очередь вручную", id);
        return notification;
    }

    /**
     * Возвращает в очередь до {@code limit} уведомлений {@code FAILED}, самые старые первыми.
     *
     * @return сколько уведомлений возвращено
     * @throws InvalidRetryLimitException {@code limit} вне диапазона 1..{@value #MAX_LIMIT}
     */
    @Transactional
    public int retryFailed(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidRetryLimitException(limit);
        }
        List<Notification> failed = repository.lockFailed(limit);
        failed.forEach(Notification::requeue);
        if (!failed.isEmpty()) {
            log.info("Возвращено в очередь вручную уведомлений: {}", failed.size());
        }
        return failed.size();
    }
}
