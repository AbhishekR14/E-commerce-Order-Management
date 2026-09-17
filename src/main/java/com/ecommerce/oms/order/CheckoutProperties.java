package com.ecommerce.oms.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code app.checkout.*}: how many times the facade retries a checkout that lost a reservation race. */
@ConfigurationProperties(prefix = "app.checkout")
public record CheckoutProperties(int maxRetries) {
}
