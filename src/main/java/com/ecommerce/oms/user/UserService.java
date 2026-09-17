package com.ecommerce.oms.user;

import com.ecommerce.oms.common.exception.BusinessRuleException;
import com.ecommerce.oms.common.exception.ConflictException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.common.exception.ForbiddenException;
import com.ecommerce.oms.common.exception.NotFoundException;
import com.ecommerce.oms.common.exception.UnauthorizedException;
import com.ecommerce.oms.security.JwtService;
import com.ecommerce.oms.user.dto.CreateUserRequest;
import com.ecommerce.oms.user.dto.LoginRequest;
import com.ecommerce.oms.user.dto.LoginResponse;
import com.ecommerce.oms.user.dto.RegisterRequest;
import com.ecommerce.oms.user.dto.UpdateUserRequest;
import com.ecommerce.oms.user.dto.UserResponse;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.WarehouseService;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private static final String TOKEN_TYPE = "Bearer";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final WarehouseService warehouseService;

    // ---- public auth --------------------------------------------------------------------------

    @Transactional
    public UserResponse register(RegisterRequest request) {
        User user = newUser(request.email(), request.password(), request.fullName(), Role.CUSTOMER, null);
        log.info("Registered customer {}", user.getId());
        return UserMapper.toResponse(user);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(normaliseEmail(request.email()))
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new UnauthorizedException(ErrorCode.INVALID_CREDENTIALS,
                        "Invalid email or password"));
        if (!user.isActive()) {
            throw new ForbiddenException("User account is inactive");
        }
        String token = jwtService.issue(user);
        return new LoginResponse(token, TOKEN_TYPE, jwtService.ttl().toSeconds(), UserMapper.toResponse(user));
    }

    @Transactional(readOnly = true)
    public UserResponse getById(Long id) {
        return UserMapper.toResponse(requireUser(id));
    }

    // ---- admin --------------------------------------------------------------------------------

    @Transactional
    public UserResponse createByAdmin(CreateUserRequest request) {
        validateRoleAndWarehouse(request.role(), request.warehouseId());
        if (request.warehouseId() != null) {
            warehouseService.requireActive(request.warehouseId());
        }
        User user = newUser(request.email(), request.password(), request.fullName(), request.role(),
                request.warehouseId());
        log.info("Admin created {} user {}", user.getRole(), user.getId());
        return UserMapper.toResponse(user);
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> list(Role role, Pageable pageable) {
        Page<User> page = role == null ? userRepository.findAll(pageable) : userRepository.findByRole(role, pageable);
        return page.map(UserMapper::toResponse);
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        User user = requireUser(id);
        if (request.warehouseId() != null) {
            if (user.getRole() != Role.WAREHOUSE_STAFF) {
                throw new BusinessRuleException(ErrorCode.USER_ROLE_INVALID,
                        "warehouseId can only be set for WAREHOUSE_STAFF users");
            }
            warehouseService.requireActive(request.warehouseId());
            user.setWarehouseId(request.warehouseId());
        }
        if (request.active() != null) {
            user.setActive(request.active());
        }
        return UserMapper.toResponse(user);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private User newUser(String email, String rawPassword, String fullName, Role role, Long warehouseId) {
        String normalised = normaliseEmail(email);
        if (userRepository.existsByEmail(normalised)) {
            throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, "Email already registered");
        }
        User user = new User();
        user.setEmail(normalised);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setFullName(fullName.trim());
        user.setRole(role);
        user.setWarehouseId(warehouseId);
        user.setActive(true);
        // A concurrent duplicate slips past existsByEmail but hits uk_users_email -> 409 via the handler.
        return userRepository.save(user);
    }

    private static void validateRoleAndWarehouse(Role role, Long warehouseId) {
        switch (role) {
            case CUSTOMER -> throw new BusinessRuleException(ErrorCode.USER_ROLE_INVALID,
                    "Admins can only create ADMIN or WAREHOUSE_STAFF users; customers self-register");
            case WAREHOUSE_STAFF -> {
                if (warehouseId == null) {
                    throw new BusinessRuleException(ErrorCode.USER_ROLE_INVALID,
                            "warehouseId is required for WAREHOUSE_STAFF users");
                }
            }
            case ADMIN -> {
                if (warehouseId != null) {
                    throw new BusinessRuleException(ErrorCode.USER_ROLE_INVALID,
                            "warehouseId must be null for ADMIN users");
                }
            }
        }
    }

    private User requireUser(Long id) {
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException("User", id));
    }

    static String normaliseEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
