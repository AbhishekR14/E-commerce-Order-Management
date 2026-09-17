package com.ecommerce.oms.common.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller: each endpoint provokes one exception so that {@link GlobalExceptionHandlerTest}
 * can assert the resulting ProblemDetail. Lives in src/test, so it never ships.
 */
@RestController
@RequestMapping("/probe")
class ErrorProbeController {

    record Body(@NotBlank String name, @Positive int quantity) {
    }

    @GetMapping("/not-found")
    String notFound() {
        throw new NotFoundException("Order", 42);
    }

    @GetMapping("/forbidden")
    String forbidden() {
        throw new ForbiddenException("Not your warehouse");
    }

    @GetMapping("/conflict")
    String conflict() {
        throw new ConflictException(ErrorCode.INSUFFICIENT_STOCK, "Only 3 units of PH-001 available");
    }

    @GetMapping("/business-rule")
    String businessRule() {
        throw new BusinessRuleException(ErrorCode.COUPON_INVALID, "Coupon expired");
    }

    @PostMapping("/body")
    String body(@Valid @RequestBody Body body) {
        return "ok";
    }

    @GetMapping("/params")
    String params(@RequestParam @Min(1) int page) {
        return "ok";
    }

    @GetMapping("/required-param")
    String requiredParam(@RequestParam String q) {
        return q;
    }

    @GetMapping("/typed/{id}")
    String typed(@PathVariable Long id) {
        return String.valueOf(id);
    }

    @PostMapping("/checkout")
    String checkout(@RequestHeader("Idempotency-Key") String key) {
        return key;
    }

    @GetMapping("/other-header")
    String otherHeader(@RequestHeader("X-Trace") String trace) {
        return trace;
    }

    @GetMapping("/optimistic-lock")
    String optimisticLock() {
        throw new OptimisticLockingFailureException("stale");
    }

    @GetMapping("/data-integrity")
    String dataIntegrity() {
        throw new DataIntegrityViolationException("unique violation: users_email_key");
    }

    @GetMapping("/boom")
    String boom() {
        throw new IllegalStateException("secret internal detail");
    }
}
