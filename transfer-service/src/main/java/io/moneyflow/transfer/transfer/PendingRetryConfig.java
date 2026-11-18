package io.moneyflow.transfer.transfer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Регистрирует настройки добивания всегда (нужны и выключенному режиму: их проверка идёт на старте). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PendingRetryProperties.class)
public class PendingRetryConfig {
}
