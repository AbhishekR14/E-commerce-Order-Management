package com.ecommerce.oms.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI metadata, the bearer scheme (use Authorize in Swagger UI with the token from /auth/login) and tag order. */
@Configuration
public class OpenApiConfig {

    public static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI omsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("E-commerce Order Management API")
                        .version("v1")
                        .description("""
                                Catalog, cart, checkout, multi-warehouse inventory (no overselling), fulfilment, \
                                discounts and taxes, cancellations, returns and refunds.

                                Log in with POST /api/v1/auth/login, then click Authorize and paste the accessToken. \
                                Demo users (H2 run): admin@oms.test / Admin@123, alice@oms.test / Customer@123, \
                                staff.blr@oms.test / Staff@123. All timestamps are UTC ISO-8601."""))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("JWT from POST /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .tags(List.of(
                        tag("Auth", "Customer registration and login"),
                        tag("Users", "The current user"),
                        tag("Catalog", "Public browsing"),
                        tag("Cart", "The customer's cart and price quotes"),
                        tag("Checkout", "Place an order from the cart"),
                        tag("Orders", "The customer's own orders and cancellations"),
                        tag("Returns", "Request returns of delivered items"),
                        tag("Notifications", "In-app notifications for the current user"),
                        tag("Warehouse - Shipments", "Pack, ship and deliver shipments of your warehouse"),
                        tag("Warehouse - Returns", "Decide on and receive returns assigned to your warehouse"),
                        tag("Admin - Users", "Manage admin and warehouse staff accounts"),
                        tag("Admin - Catalog", "Manage categories and products"),
                        tag("Admin - Warehouses", "Manage warehouses"),
                        tag("Admin - Inventory", "Stock levels, adjustments and the movement ledger"),
                        tag("Admin - Coupons", "Manage discount coupons"),
                        tag("Admin - Orders", "Search, inspect and cancel any order"),
                        tag("Admin - Audit", "Who did what, from the after-commit event stream")));
    }

    private static Tag tag(String name, String description) {
        return new Tag().name(name).description(description);
    }
}
