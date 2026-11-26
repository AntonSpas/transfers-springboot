package io.moneyflow.notification.dispatch;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Регистрирует настройки отправки всегда (нужны и выключенному режиму: их проверка идёт на старте). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DispatchProperties.class)
public class DispatchPropertiesConfig {
}
