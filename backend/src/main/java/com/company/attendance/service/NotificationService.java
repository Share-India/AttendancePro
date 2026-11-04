package com.company.attendance.service;

import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.notification.NotificationResponse;
import com.company.attendance.entity.Notification;
import com.company.attendance.entity.User;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.NotificationType;
import com.company.attendance.exception.BusinessException;
import com.company.attendance.repository.NotificationRepository;
import com.company.attendance.repository.UserRepository;
import com.company.attendance.security.UserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Writing and reading in-app notifications.
 *
 * <p>Nothing here knows what a leave request is. Callers describe what happened
 * in plain values and this decides how to store it, which keeps the dependency
 * arrow pointing one way.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;

    public NotificationService(NotificationRepository notificationRepository,
                               UserRepository userRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
    }

    // -----------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------

    /**
     * Notifies the user behind an employee record.
     *
     * <p>Runs in its own transaction, and swallows its own failures. Both are
     * deliberate: a notification is a side effect of the thing that actually
     * matters, and a leave approval must never be rolled back because the
     * message about it could not be written. The alternative — letting this
     * throw — means the least important part of the flow can veto the most
     * important one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyEmployee(Long employeeId,
                               NotificationType type,
                               String title,
                               String body,
                               String link,
                               String entityType,
                               Object entityId,
                               Long actorUserId,
                               String actorName) {

        if (employeeId == null) {
            return;
        }
        try {
            Optional<User> recipient = userRepository.findByEmployeeId(employeeId);
            if (recipient.isEmpty()) {
                // Perfectly normal: an employee record can exist before anyone
                // has been given a login for it.
                log.debug("No user account for employee {}; notification skipped", employeeId);
                return;
            }
            if (!recipient.get().isActive()) {
                return;
            }
            write(recipient.get().getId(), type, title, body, link, entityType, entityId,
                    actorUserId, actorName);

        } catch (Exception e) {
            log.error("Could not write a {} notification for employee {}", type, employeeId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyUser(Long userId,
                           NotificationType type,
                           String title,
                           String body,
                           String link,
                           String entityType,
                           Object entityId,
                           Long actorUserId,
                           String actorName) {
        try {
            write(userId, type, title, body, link, entityType, entityId, actorUserId, actorName);
        } catch (Exception e) {
            log.error("Could not write a {} notification for user {}", type, userId, e);
        }
    }

    private void write(Long recipientUserId,
                       NotificationType type,
                       String title,
                       String body,
                       String link,
                       String entityType,
                       Object entityId,
                       Long actorUserId,
                       String actorName) {

        // Telling someone about their own action is noise, not information.
        if (actorUserId != null && actorUserId.equals(recipientUserId)) {
            return;
        }

        Notification notification = new Notification();
        notification.setRecipientUserId(recipientUserId);
        notification.setType(type);
        notification.setCategory(type.getCategory());
        notification.setTitle(truncate(title, 200));
        notification.setBody(truncate(body, 1000));
        notification.setLink(truncate(link, 300));
        notification.setEntityType(truncate(entityType, 50));
        notification.setEntityId(entityId == null ? null : truncate(String.valueOf(entityId), 50));
        notification.setActorUserId(actorUserId);
        notification.setActorName(truncate(
                actorName != null ? actorName : displayNameOf(actorUserId), 150));

        notificationRepository.save(notification);
    }

    // -----------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(UserPrincipal principal,
                                                   boolean unreadOnly,
                                                   Pageable pageable) {
        Long userId = requireUser(principal);

        Page<Notification> page = unreadOnly
                ? notificationRepository.findByRecipientUserIdAndReadFalseOrderByCreatedAtDesc(userId, pageable)
                : notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(userId, pageable);

        return PageResponse.of(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UserPrincipal principal) {
        return notificationRepository.countByRecipientUserIdAndReadFalse(requireUser(principal));
    }

    @Transactional
    public NotificationResponse markRead(UserPrincipal principal, Long id) {
        Long userId = requireUser(principal);

        // Scoped by recipient in the query itself, so one user cannot mark
        // another's notification read by guessing an id.
        Notification notification = notificationRepository.findByIdAndRecipientUserId(id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "That notification does not exist."));

        if (!notification.isRead()) {
            notification.setRead(true);
            notification.setReadAt(Instant.now());
        }
        return toResponse(notification);
    }

    @Transactional
    public int markAllRead(UserPrincipal principal) {
        return notificationRepository.markAllRead(requireUser(principal), Instant.now());
    }

    // -----------------------------------------------------------------

    private Long requireUser(UserPrincipal principal) {
        if (principal == null || principal.getUserId() == null) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED, "Not signed in.");
        }
        return principal.getUserId();
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType().name(),
                notification.getCategory().name(),
                notification.getTitle(),
                notification.getBody(),
                notification.getLink(),
                notification.getEntityType(),
                notification.getEntityId(),
                notification.getActorName(),
                notification.isRead(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }

    /**
     * What to call the person who caused this.
     *
     * <p>Resolved here rather than passed in by every caller: the token carries
     * a username like EMP0002, and "EMP0002 approved your leave" is not what
     * anybody wants to read. Falls back to the username when there is no
     * employee record behind the account.
     */
    private String displayNameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId)
                .map(user -> user.getEmployee() != null
                        ? user.getEmployee().getFullName()
                        : user.getUsername())
                .orElse(null);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
