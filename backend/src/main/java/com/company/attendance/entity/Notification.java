package com.company.attendance.entity;

import com.company.attendance.enums.NotificationCategory;
import com.company.attendance.enums.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One thing a person is told.
 *
 * <p>The text is frozen at write time rather than rendered on read. A message
 * that said "Priya Sharma approved your leave" must still say that after Priya
 * has left and her record is deactivated; regenerating it from live data would
 * quietly rewrite what someone was told every time the org chart changed.
 */
@Entity
@Table(
        name = "notifications",
        indexes = {
                @Index(name = "idx_notification_recipient",
                        columnList = "recipient_user_id, is_read, created_at"),
                @Index(name = "idx_notification_entity", columnList = "entity_type, entity_id")
        })
@Getter
@Setter
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Addressed to a user, not an employee: a system account can be told things too. */
    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private NotificationCategory category;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "body", length = 1000)
    private String body;

    /** App-relative path, stored so an old notification survives a route change. */
    @Column(name = "link", length = 300)
    private String link;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "entity_id", length = 50)
    private String entityId;

    /** Null for anything a scheduled job raised: no human caused it. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_name", length = 150)
    private String actorName;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
