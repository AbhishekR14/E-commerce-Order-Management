package com.ecommerce.oms.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Minimal OpenAPI metadata; the bearer security scheme and tags are added in phase 11. */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI omsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("E-commerce Order Management API")
                .version("v1")
                .description("Catalog, cart, checkout, multi-warehouse inventory, fulfillment, returns and refunds."));
    }
}
