package com.ecommerce.oms.common.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.security.SecurityConfig;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Drives every branch of {@link GlobalExceptionHandler} through {@link ErrorProbeController} and checks the
 * RFC 7807 body: status, title, detail, instance, {@code code}, {@code timestamp} and {@code errors}.
 */
@WebMvcTest(controllers = ErrorProbeController.class)
@Import({SecurityConfig.class, GlobalExceptionHandlerTest.FixedClock.class})
class GlobalExceptionHandlerTest {

    static final Instant NOW = Instant.parse("2026-09-17T10:15:30Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("ApiException -> its code's status, title, detail, code, instance and timestamp")
    void apiException_notFound() throws Exception {
        mvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Order 42 not found"))
                .andExpect(jsonPath("$.instance").value("/probe/not-found"))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.timestamp").value("2026-09-17T10:15:30Z"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void forbiddenException_403() throws Exception {
        mvc.perform(get("/probe/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.detail").value("Not your warehouse"));
    }

    @Test
    void conflictException_409_withSpecificCode() throws Exception {
        mvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.title").value("Insufficient stock"))
                .andExpect(jsonPath("$.detail").value("Only 3 units of PH-001 available"));
    }

    @Test
    void businessRuleException_422() throws Exception {
        mvc.perform(get("/probe/business-rule"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("COUPON_INVALID"))
                .andExpect(jsonPath("$.detail").value("Coupon expired"));
    }

    @Test
    @DisplayName("Bean Validation on a body -> 400 VALIDATION_FAILED with a sorted errors list")
    void bodyValidation_400_withErrors() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"))
                .andExpect(jsonPath("$.errors[1].field").value("quantity"))
                .andExpect(jsonPath("$.errors[1].message").value("must be greater than 0"));
    }

    @Test
    void malformedJson_400() throws Exception {
        mvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("Malformed request body"));
    }

    @Test
    @DisplayName("Constraint on a request parameter -> 400 VALIDATION_FAILED naming the parameter")
    void paramValidation_400() throws Exception {
        mvc.perform(get("/probe/params").param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("page"))
                .andExpect(jsonPath("$.errors[0].message").value("must be greater than or equal to 1"));
    }

    @Test
    void missingParam_400() throws Exception {
        mvc.perform(get("/probe/required-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(containsString("q")));
    }

    @Test
    void typeMismatch_400() throws Exception {
        mvc.perform(get("/probe/typed/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value("Parameter id must be a valid Long"));
    }

    @Test
    void missingIdempotencyKey_400_withDedicatedCode() throws Exception {
        mvc.perform(post("/probe/checkout"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"))
                .andExpect(jsonPath("$.title").value("Idempotency-Key header is required"));
    }

    @Test
    void missingOtherHeader_400_validationFailed() throws Exception {
        mvc.perform(get("/probe/other-header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.detail").value(containsString("X-Trace")));
    }

    @Test
    void optimisticLock_409_concurrentModification() throws Exception {
        mvc.perform(get("/probe/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void dataIntegrity_409_duplicateResource() throws Exception {
        mvc.perform(get("/probe/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.detail").value(not(containsString("users_email_key"))));
    }

    @Test
    @DisplayName("Unexpected exception -> 500 INTERNAL_ERROR without leaking the message or stack trace")
    void unexpected_500_noLeak() throws Exception {
        mvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                .andExpect(content().string(not(containsString("secret internal detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void unknownRoute_404_notFound() throws Exception {
        mvc.perform(get("/probe/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("No endpoint GET /probe/does-not-exist"));
    }

    @Test
    void wrongMethod_405() throws Exception {
        mvc.perform(post("/probe/not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.title").value("Method not allowed"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.timestamp").value("2026-09-17T10:15:30Z"));
    }
}
