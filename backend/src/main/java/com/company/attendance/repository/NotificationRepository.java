package com.company.attendance.repository;

import com.company.attendance.entity.Notification;
import com.company.attendance.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientUserIdOrderByCreatedAtDesc(Long recipientUserId, Pageable pageable);

    Page<Notification> findByRecipientUserIdAndReadFalseOrderByCreatedAtDesc(
            Long recipientUserId, Pageable pageable);

    long countByRecipientUserIdAndReadFalse(Long recipientUserId);

    Optional<Notification> findByIdAndRecipientUserId(Long id, Long recipientUserId);

    /**
     * Whether this person has already been told this, about this day.
     *
     * <p>The reminder sweep runs every few minutes and the condition it looks
     * for -- no punch-in -- stays true until the person acts. Without a record
     * of what has been sent, somebody who is genuinely away would be mailed
     * every quarter of an hour until their shift ended. The notification row
     * is that record; it carries the date it is about, so a restart does not
     * start the chasing over.
     */
    boolean existsByRecipientUserIdAndTypeAndEntityId(Long recipientUserId,
                                                      NotificationType type,
                                                      String entityId);

    /**
     * Marks everything unread as read in one statement.
     *
     * <p>A bulk update rather than a read-modify-write loop: someone coming
     * back from leave can easily have hundreds, and loading them all to flip a
     * boolean would be pure waste.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE Notification n
               SET n.read = true, n.readAt = :readAt
             WHERE n.recipientUserId = :userId
               AND n.read = false
            """)
    int markAllRead(@Param("userId") Long userId, @Param("readAt") Instant readAt);
}
