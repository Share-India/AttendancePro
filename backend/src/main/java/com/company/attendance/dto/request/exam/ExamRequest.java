package com.company.attendance.dto.request.exam;

import com.company.attendance.enums.ExamFeedbackVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Creating or editing an exam.
 *
 * <p>No employee id anywhere, as everywhere else in this application: the
 * person setting the exam is whoever is signed in, and who sits it comes from
 * the audience rules rather than a list of ids the client made up.
 *
 * <p>Every marking parameter is here rather than fixed in code. The settled
 * defaults — 25 minutes, 5 marks a question, pass at 40 — are applied by the
 * form, not by the server refusing anything else, because the first request
 * after launch is always "can this one be different".
 */
public record ExamRequest(

        @NotBlank(message = "Give the exam a code, for example PK-W42")
        @Size(max = 40, message = "That code is too long")
        String examCode,

        @NotBlank(message = "Give the exam a title")
        @Size(max = 200, message = "That title is too long")
        String title,

        @Size(max = 1000, message = "That description is too long")
        String description,

        @Size(max = 4000, message = "Those instructions are too long")
        String instructions,

        @NotNull(message = "When does the exam open?")
        Instant windowStart,

        @NotNull(message = "When does the exam close?")
        Instant windowEnd,

        @Min(value = 1, message = "An exam needs at least a minute")
        @Max(value = 480, message = "Eight hours is the longest an exam can run")
        int durationMinutes,

        /**
         * How long before the window shuts that starting is refused. Starting
         * five minutes before the end gives five minutes for a twenty-five
         * minute paper, which is arithmetically right and reliably produces a
         * complaint.
         */
        @Min(value = 0, message = "This cannot be negative")
        @Max(value = 240, message = "That cut-off is longer than most exams")
        int entryClosesBeforeMinutes,

        @Min(value = 1, message = "A question has to be worth at least one mark")
        int marksPerQuestion,

        @Min(value = 0, message = "Negative marking is given as a positive number of marks to deduct")
        int negativeMarks,

        @Min(value = 0, message = "The pass mark cannot be negative")
        int passMarks,

        boolean shuffleQuestions,

        /**
         * The structural defence against a paper that can be passed by ticking
         * B every time. It works whether or not anybody reads the quality
         * report, so turning it off should be a deliberate act.
         */
        boolean shuffleOptions,

        boolean partialCredit,

        @NotNull(message = "Choose when the worked answers become readable")
        ExamFeedbackVisibility feedbackVisibility,

        @Valid
        List<AudienceRule> audience) {

    /** One audience rule. {@code scopeId} is null only for GLOBAL. */
    public record AudienceRule(
            @NotBlank(message = "Choose who the exam is for")
            String scopeType,
            Long scopeId) {
    }
}
