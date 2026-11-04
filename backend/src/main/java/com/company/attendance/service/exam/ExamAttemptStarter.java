package com.company.attendance.service.exam;

import com.company.attendance.entity.ExamAttempt;
import com.company.attendance.repository.ExamAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Inserts an attempt, or reports that somebody else just did.
 *
 * <p>Separate from the service, and in its own transaction, because of how JPA
 * behaves: once a constraint violation is thrown the surrounding transaction is
 * marked rollback-only, so catching it and carrying on where it happened would
 * fail at commit instead. Isolating the insert means the caller can lose the
 * race, read the winner's row and hand that back — which is what a
 * double-tapped Start button needs.
 *
 * <p>Same pattern, and the same reason, as the punch-in path.
 */
@Component
public class ExamAttemptStarter {

    private static final Logger log = LoggerFactory.getLogger(ExamAttemptStarter.class);

    private final ExamAttemptRepository attemptRepository;

    public ExamAttemptStarter(ExamAttemptRepository attemptRepository) {
        this.attemptRepository = attemptRepository;
    }

    /**
     * @return the saved attempt, or empty when the unique key on
     *         (participant, attempt number) says one already exists
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ExamAttempt> insert(ExamAttempt attempt) {
        try {
            return Optional.of(attemptRepository.saveAndFlush(attempt));
        } catch (DataIntegrityViolationException alreadyStarted) {
            log.debug("Attempt {} for participant {} already exists",
                    attempt.getAttemptNumber(), attempt.getParticipant().getId());
            return Optional.empty();
        }
    }
}
