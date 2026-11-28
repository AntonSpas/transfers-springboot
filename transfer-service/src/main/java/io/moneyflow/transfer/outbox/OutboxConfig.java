package io.moneyflow.transfer.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Регистрирует настройки публикации и очистки outbox всегда: их проверка идёт на старте и в выключенном режиме. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({OutboxProperties.class, OutboxCleanupProperties.class})
public class OutboxConfig {
}
