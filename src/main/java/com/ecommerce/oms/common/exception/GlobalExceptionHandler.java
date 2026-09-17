package com.ecommerce.oms.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every exception to an RFC 7807 {@link ProblemDetail} with two extra properties, {@code code} and
 * {@code timestamp}, plus {@code errors} for validation failures (docs/design/03-api-spec.md, Errors).
 * Unexpected exceptions are logged with their stack trace, but the response body never contains it.
 */
@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class GlobalExceptionHandler {

    /** One entry of the {@code errors} list on validation failures. */
    public record FieldViolation(String field, String message) {
    }

    private static final Comparator<FieldViolation> BY_FIELD =
            Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message);

    private final Clock clock;

    // ---- business errors ----------------------------------------------------------------------

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex, HttpServletRequest request) {
        return problem(ex.getCode(), ex.getMessage(), request);
    }

    // ---- 400: validation and malformed input --------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toViolation)
                .sorted(BY_FIELD)
                .toList();
        return validationProblem(errors, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleParamValidation(ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldViolation> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(leafName(v.getPropertyPath().toString()), v.getMessage()))
                .sorted(BY_FIELD)
                .toList();
        return validationProblem(errors, request);
    }

    /** Constraints on {@code @RequestParam}/{@code @PathVariable} (Spring MVC built-in method validation). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest request) {
        List<FieldViolation> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(parameterName(result), error.getDefaultMessage())))
                .sorted(BY_FIELD)
                .toList();
        return validationProblem(errors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return problem(ErrorCode.VALIDATION_FAILED, "Malformed request body", request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        // The checkout Idempotency-Key has its own code; any other missing header is a plain validation error.
        ErrorCode code = "Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())
                ? ErrorCode.IDEMPOTENCY_KEY_MISSING
                : ErrorCode.VALIDATION_FAILED;
        return problem(code, "Missing required header " + ex.getHeaderName(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return problem(ErrorCode.VALIDATION_FAILED, "Missing required parameter " + ex.getParameterName(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String expected = ex.getRequiredType() == null ? "the expected type" : ex.getRequiredType().getSimpleName();
        return problem(ErrorCode.VALIDATION_FAILED,
                "Parameter " + ex.getName() + " must be a valid " + expected, request);
    }

    // ---- 404 / 405 raised by Spring MVC itself ------------------------------------------------

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return problem(ErrorCode.NOT_FOUND,
                "No endpoint " + request.getMethod() + " " + request.getRequestURI(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                  HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage());
        problem.setTitle("Method not allowed");
        decorate(problem, ErrorCode.VALIDATION_FAILED, request);
        return problem;
    }

    // ---- 409 raised by the persistence layer --------------------------------------------------

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        log.warn("Optimistic lock failure on {} {}", request.getMethod(), request.getRequestURI());
        return problem(ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified concurrently; please retry", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
        return problem(ErrorCode.DUPLICATE_RESOURCE, "The request conflicts with existing data", request);
    }

    // ---- fallback -----------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", request);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private ProblemDetail validationProblem(List<FieldViolation> errors, HttpServletRequest request) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "Request validation failed", request);
        problem.setProperty("errors", errors);
        return problem;
    }

    private ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        decorate(problem, code, request);
        return problem;
    }

    private void decorate(ProblemDetail problem, ErrorCode code, HttpServletRequest request) {
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        problem.setProperty("timestamp", clock.instant());
    }

    private static FieldViolation toViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }

    private static String parameterName(ParameterValidationResult result) {
        String name = result.getMethodParameter().getParameterName();
        return name != null ? name : "arg" + result.getMethodParameter().getParameterIndex();
    }

    /** Turns a method-parameter path such as {@code search.minPrice} into just {@code minPrice}. */
    private static String leafName(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
    }
}
