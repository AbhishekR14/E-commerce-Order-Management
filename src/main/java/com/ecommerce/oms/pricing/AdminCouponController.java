package com.ecommerce.oms.pricing;

import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.pricing.dto.CouponRequest;
import com.ecommerce.oms.pricing.dto.CouponResponse;
import com.ecommerce.oms.pricing.dto.CouponStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/coupons")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin - Coupons", description = "Manage discount coupons")
public class AdminCouponController {

    private final CouponService couponService;

    @PostMapping
    @Operation(summary = "Create a coupon")
    public ResponseEntity<CouponResponse> create(@Valid @RequestBody CouponRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(couponService.create(request));
    }

    @GetMapping
    @Operation(summary = "List coupons with their usage counts")
    public PageResponse<CouponResponse> list(
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(couponService.list(pageable));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a coupon (the code is immutable)")
    public CouponResponse update(@PathVariable Long id, @Valid @RequestBody CouponRequest request) {
        return couponService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Activate or deactivate a coupon")
    public CouponResponse setStatus(@PathVariable Long id, @Valid @RequestBody CouponStatusRequest request) {
        return couponService.setActive(id, request.active());
    }
}
