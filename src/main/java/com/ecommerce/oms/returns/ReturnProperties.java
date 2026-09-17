package com.ecommerce.oms.returns;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.returns.window-days}: how long after delivery a return may be requested. */
@ConfigurationProperties(prefix = "app.returns")
public record ReturnProperties(int windowDays) {
}
