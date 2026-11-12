package com.company.attendance.service;

import com.company.attendance.config.MailProperties;
import com.company.attendance.dto.response.PageResponse;
import com.company.attendance.dto.response.notification.NotificationResponse;
import com.company.attendance.entity.Notification;
import com.company.attendance.entity.User;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.enums.NotificationCategory;
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
import java.util.Set;

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

    /**
     * Which categories are worth an email as well as an in-app notification.
     *
     * <p>APPROVAL is something waiting on the recipient to act; LEAVE and
     * REQUEST are the outcome of something they asked for. Both are worth
     * interrupting somebody for. ATTENDANCE and SYSTEM are not: a missing
     * punch-out, a meeting and an exam are all things people meet when they
     * next open the app, and mailing them turns a manager with twenty reports
     * into somebody who filters this sender to a folder.
     *
     * <p>There is a notification_preferences table with an `email` column and
     * no code behind it, so this is not yet a per-person choice. Until it is,
     * the category is the whole policy.
     */
    private static final Set<NotificationCategory> EMAILED_CATEGORIES = Set.of(
            NotificationCategory.APPROVAL,
            NotificationCategory.LEAVE,
            NotificationCategory.REQUEST);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final MailProperties mailProperties;
    private final EmailTemplates emailTemplates;
    private final EmailService emailService;

    public NotificationService(NotificationRepository notificationRepository,
                               UserRepository userRepository,
                               MailProperties mailProperties,
                               EmailTemplates emailTemplates,
                               EmailService emailService) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.mailProperties = mailProperties;
        this.emailTemplates = emailTemplates;
        this.emailService = emailService;
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
        emailIfWorthIt(recipientUserId, type, title, body, link);
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

    /**
     * Also mails the notification, when its category earns one.
     *
     * <p>Swallows its own failures for the same reason the rest of this class
     * does: the approval has already committed, and a mail server being down
     * must not be able to undo it. EmailService does not throw either, so this
     * is belt and braces around the lookup rather than the send.
     */
    private void emailIfWorthIt(Long recipientUserId, NotificationType type,
                                String title, String body, String link) {
        if (!EMAILED_CATEGORIES.contains(type.getCategory())) {
            return;
        }
        try {
            User recipient = userRepository.findById(recipientUserId).orElse(null);
            if (recipient == null) {
                return;
            }
            String address = recipient.getEmployee() != null
                    && recipient.getEmployee().getOfficialEmail() != null
                    && !recipient.getEmployee().getOfficialEmail().isBlank()
                    ? recipient.getEmployee().getOfficialEmail()
                    : recipient.getEmail();

            String firstName = recipient.getEmployee() != null
                    && recipient.getEmployee().getFirstName() != null
                    ? recipient.getEmployee().getFirstName()
                    : recipient.getUsername();

            // The stored link is app-relative, because it has to keep working
            // if the hostname changes. Mail cannot use a relative path.
            String absolute = mailProperties.link(link == null ? "/dashboard" : link);

            var message = type.getCategory() == NotificationCategory.APPROVAL
                    ? emailTemplates.approvalRequested(firstName, title, body, absolute)
                    : emailTemplates.requestDecided(firstName, title, body, absolute);

            emailService.send(address, message.subject(), message.html(), message.text());
        } catch (Exception e) {
            log.error("Could not email the {} notification to user {}", type, recipientUserId, e);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
