package com.ecommerce.oms.notification;

import com.ecommerce.oms.common.web.PageResponse;
import com.ecommerce.oms.notification.dto.NotificationResponse;
import com.ecommerce.oms.security.AuthUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "In-app notifications for the current user")
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    @Operation(summary = "Own notifications, newest first")
    public PageResponse<NotificationResponse> list(
            @AuthenticationPrincipal AuthUser principal,
            @RequestParam(required = false, defaultValue = "false") boolean unreadOnly,
            @ParameterObject @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(notificationService.list(principal.id(), unreadOnly, pageable));
    }

    @PatchMapping("/{id}/read")
    @Operation(summary = "Mark one of your notifications as read")
    public NotificationResponse markRead(@AuthenticationPrincipal AuthUser principal, @PathVariable Long id) {
        return notificationService.markRead(principal.id(), id);
    }
}
