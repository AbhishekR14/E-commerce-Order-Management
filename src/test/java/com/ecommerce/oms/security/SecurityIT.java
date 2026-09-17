package com.ecommerce.oms.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.user.entity.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * URL rules and token handling from docs/design/10-security-rbac.md. Endpoints that do not exist yet
 * (/warehouse/**, /cart/**, /checkout) still prove the URL rule: the filter chain answers before MVC's 404.
 */
class SecurityIT extends AbstractIntegrationTest {

    @Autowired
    JwtProperties jwtProperties;

    @Test
    void noToken_401_problemDetail() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/v1/users/me"))
                .andExpect(jsonPath("$.timestamp").isString());

        mvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void malformedToken_401() throws Exception {
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Basic abc"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a token issued in the past beyond its TTL -> 401")
    void expiredToken_401() throws Exception {
        User customer = data.customer(1);
        Instant past = clock.instant().minus(Duration.ofMinutes(jwtProperties.ttlMinutes() + 5));
        JwtService pastIssuer = new JwtService(jwtProperties, Clock.fixed(past, ZoneOffset.UTC));

        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + pastIssuer.issue(customer)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void tamperedToken_401() throws Exception {
        User customer = data.customer(1);
        JwtService otherSecret = new JwtService(
                new JwtProperties("some-other-secret-0123456789abcdef0123456789", 60), clock);

        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherSecret.issue(customer)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customer_adminAndWarehouseAreas_403() throws Exception {
        User customer = data.customer(1);

        mvc.perform(getJson("/api/v1/admin/users", customer))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.status").value(403));

        mvc.perform(getJson("/api/v1/warehouse/shipments", customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void staff_customerAreas_403() throws Exception {
        User staff = data.staff(data.warehouse("BLR-1", 1));

        mvc.perform(postJson("/api/v1/checkout", "{}", staff))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(getJson("/api/v1/cart", staff))
                .andExpect(status().isForbidden());
        mvc.perform(getJson("/api/v1/orders", staff))
                .andExpect(status().isForbidden());
        mvc.perform(getJson("/api/v1/admin/users", staff))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("admin may enter /warehouse/**")
    void admin_warehouseArea_allowed() throws Exception {
        User admin = data.admin();

        mvc.perform(getJson("/api/v1/warehouse/shipments", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void publicEndpoints_workWithoutToken() throws Exception {
        // validation error, not 401: the request reached the controller
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("documented trade-off: a deactivated user's existing token keeps working until it expires")
    void deactivatedUser_existingTokenStillValid() throws Exception {
        User customer = data.customer(1);
        String token = bearer(customer);
        data.deactivate(customer);

        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }
}
