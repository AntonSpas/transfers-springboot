package io.moneyflow.notification.consumer;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Регистрирует настройки потребителя всегда (нужны и выключенному режиму: их проверка идёт на старте). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConsumerProperties.class)
public class ConsumerPropertiesConfig {
}
