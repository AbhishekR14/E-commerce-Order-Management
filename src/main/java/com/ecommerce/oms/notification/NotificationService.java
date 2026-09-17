package com.ecommerce.oms.notification;

import com.ecommerce.oms.common.exception.NotFoundException;
import com.ecommerce.oms.notification.dto.NotificationResponse;
import com.ecommerce.oms.notification.entity.Notification;
import com.ecommerce.oms.user.Role;
import com.ecommerce.oms.user.UserRepository;
import com.ecommerce.oms.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Stores notifications (no delivery channel, doc 00) and serves the customer endpoints. */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    // ---- writing (from listeners) ------------------------------------------------------------

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyUser(Long userId, NotificationType type, String title, String message,
                           String referenceType, Long referenceId) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        n.setReferenceType(referenceType);
        n.setReferenceId(referenceId);
        n.setRead(false);
        notificationRepository.save(n);
        log.info("NOTIFY user={} type={} ref={}:{} title=\"{}\"", userId, type, referenceType, referenceId, title);
    }

    /** Every active staff member of the warehouse gets the same notification. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyWarehouseStaff(Long warehouseId, NotificationType type, String title, String message,
                                     String referenceType, Long referenceId) {
        for (User staff : userRepository.findAllByRoleAndWarehouseIdAndActiveTrue(Role.WAREHOUSE_STAFF, warehouseId)) {
            notifyUser(staff.getId(), type, title, message, referenceType, referenceId);
        }
    }

    // ---- reading (endpoints) -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(Long userId, boolean unreadOnly, Pageable pageable) {
        Page<Notification> page = unreadOnly
                ? notificationRepository.findAllByUserIdAndReadFalse(userId, pageable)
                : notificationRepository.findAllByUserId(userId, pageable);
        return page.map(NotificationService::toResponse);
    }

    @Transactional
    public NotificationResponse markRead(Long userId, Long id) {
        Notification n = notificationRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new NotFoundException("Notification", id));
        n.setRead(true);
        return toResponse(n);
    }

    static NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getTitle(), n.getMessage(), n.getReferenceType(),
                n.getReferenceId(), n.isRead(), n.getCreatedAt());
    }
}
