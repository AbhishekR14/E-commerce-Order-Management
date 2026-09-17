package com.ecommerce.oms.user;

import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.user.dto.CreateUserRequest;
import com.ecommerce.oms.user.dto.UpdateUserRequest;
import com.ecommerce.oms.user.dto.UserResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin - Users", description = "Manage admin and warehouse staff accounts")
public class AdminUserController {

    private final UserService userService;

    @PostMapping
    @Operation(summary = "Create an ADMIN or WAREHOUSE_STAFF user")
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userService.createByAdmin(request));
    }

    @GetMapping
    @Operation(summary = "List users, optionally filtered by role")
    public PageResponse<UserResponse> list(
            @RequestParam(required = false) Role role,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(userService.list(role, pageable));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Activate/deactivate a user or reassign a staff member's warehouse")
    public UserResponse update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest request) {
        return userService.update(id, request);
    }
}
