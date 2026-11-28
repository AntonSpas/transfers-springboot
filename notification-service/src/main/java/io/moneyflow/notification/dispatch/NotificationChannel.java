package io.moneyflow.notification.dispatch;

import io.moneyflow.notification.notification.Notification;

/**
 * Канал доставки уведомления пользователю (email, SMS, push и т. п.). Сейчас единственная реализация
 * {@link LoggingNotificationChannel}: заглушка с записью в лог; реальные каналы вне задачи.
 *
 * <p>Исключение из {@link #send(Notification)} означает «доставить не удалось»: dispatcher повторяет отправку с
 * растущей паузой ({@code app.dispatch.retry.*}), а когда попытки кончаются, ставит статус {@code FAILED}.
 *
 * <p>Требования к реализации:
 * <ul>
 *   <li>Доставка at-least-once: одно уведомление может прийти в {@code send} повторно, если проход не записал итог
 *   до конца аренды (падение сервиса, сбой БД, слишком долгая отправка), а также при повторе после исключения,
 *   хотя провайдер мог принять сообщение (таймаут ответа). Поэтому {@link Notification#getId()}
 *   служит ключом идемпотентности: реальный канал передаёт его провайдеру, чтобы тот отсёк дубль.</li>
 *   <li>{@code send} вызывается вне транзакции БД; переданный объект отсоединён от контекста JPA, его изменения
 *   не сохраняются.</li>
 *   <li>У вызова должен быть собственный таймаут, намного меньше {@code app.dispatch.lease}.</li>
 * </ul>
 */
public interface NotificationChannel {

    void send(Notification notification);
}
