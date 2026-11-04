package com.company.attendance.service.exam;

import com.company.attendance.entity.Exam;
import com.company.attendance.entity.ExamParticipant;
import com.company.attendance.enums.ExamParticipantStatus;
import com.company.attendance.enums.ExamStatus;
import com.company.attendance.repository.ExamAttemptRepository;
import com.company.attendance.repository.ExamParticipantRepository;
import com.company.attendance.repository.ExamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Brings an exam's status up to date with its own window.
 *
 * <p><strong>There is no scheduler in this application</strong> — no
 * {@code @EnableScheduling}, no ShedLock, no cron anywhere — so nothing moves
 * an exam from PUBLISHED to OPEN to CLOSED on a timer. Instead the window is
 * the authority and the status catches up the next time anybody reads the exam.
 * The meeting module settles itself the same way, for the same reason.
 *
 * <p>That is not merely a substitute for a sweep; in one respect it is better.
 * A sweep that fails silently leaves every exam stale and nobody finds out
 * until a report looks wrong. This path runs on the read that would have shown
 * the wrong answer, so a failure is loud and immediate.
 *
 * <p>What it does <em>not</em> give is a guarantee of promptness: an exam
 * nobody looks at stays open-looking in the database. That costs nothing,
 * because every path that matters checks the window itself — a candidate
 * cannot start outside it, cannot save an answer past their expiry, and their
 * own submission is scored against the clock rather than the status.
 */
@Service
public class ExamWindowService {

    private static final Logger log = LoggerFactory.getLogger(ExamWindowService.class);

    private final ExamRepository examRepository;
    private final ExamParticipantRepository participantRepository;
    private final ExamAttemptRepository attemptRepository;
    private final ExamScoringService scoringService;

    public ExamWindowService(ExamRepository examRepository,
                             ExamParticipantRepository participantRepository,
                             ExamAttemptRepository attemptRepository,
                             ExamScoringService scoringService) {
        this.examRepository = examRepository;
        this.participantRepository = participantRepository;
        this.attemptRepository = attemptRepository;
        this.scoringService = scoringService;
    }

    /**
     * Settles one exam. Safe to call on every read: it writes nothing when the
     * status already matches the window.
     */
    @Transactional
    public Exam settle(Exam exam) {
        Instant now = Instant.now();

        if (exam.getStatus() == ExamStatus.PUBLISHED && !now.isBefore(exam.getWindowStart())) {
            exam.setStatus(ExamStatus.OPEN);
            log.info("Exam {} is now open", exam.getExamCode());
        }

        if (exam.getStatus().isLive() && !now.isBefore(exam.getWindowEnd())) {
            // Papers first, status second. Closing the exam before the open
            // attempts are scored would leave somebody submitted-but-unmarked
            // in a window that no longer admits them.
            closeExpiredAttempts(exam, now);
            exam.setStatus(ExamStatus.CLOSED);
            exam.setClosedAt(now);
            markAbsentees(exam);
            log.info("Exam {} closed", exam.getExamCode());
        }

        return examRepository.save(exam);
    }

    /**
     * Settles every exam whose window has moved on.
     *
     * <p>Called from the list reads. The query is narrow — only PUBLISHED and
     * OPEN rows whose window has started or ended — so in the normal case it
     * matches nothing and costs one indexed lookup.
     */
    @Transactional
    public void settleAllOverdue() {
        for (Exam exam : examRepository.findNeedingSettlement(Instant.now())) {
            settle(exam);
        }
    }

    /**
     * Papers still open past their own clock.
     *
     * <p>Ids are read first and each is then scored on its own transaction, so
     * a failure is one person's problem rather than everybody's. The whole
     * reason answers are durable is that something else breaking must not cost
     * somebody their morning.
     */
    private void closeExpiredAttempts(Exam exam, Instant now) {
        List<Long> expired = attemptRepository.findExpiredUnsubmitted(exam.getId(), now);
        int closed = 0;
        for (Long attemptId : expired) {
            try {
                scoringService.autoSubmitExpired(attemptId);
                closed++;
            } catch (RuntimeException failed) {
                log.error("Attempt {} could not be auto-submitted", attemptId, failed);
            }
        }
        if (closed > 0) {
            log.info("Exam {}: auto-submitted {} expired attempt(s)", exam.getExamCode(), closed);
        }
    }

    /**
     * Settles who never turned up, so the participation figure is answerable
     * the moment the exam is over rather than being inferred from an absence of
     * rows.
     */
    private void markAbsentees(Exam exam) {
        List<ExamParticipant> assigned = participantRepository
                .findByExamIdAndStatus(exam.getId(), ExamParticipantStatus.ASSIGNED);
        assigned.forEach(participant ->
                participant.setStatus(ExamParticipantStatus.NOT_ATTEMPTED));
        participantRepository.saveAll(assigned);

        if (!assigned.isEmpty()) {
            log.info("Exam {}: {} people did not attempt it", exam.getExamCode(), assigned.size());
        }
    }
}
