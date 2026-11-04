package com.company.attendance.exception;

import com.company.attendance.dto.response.ApiResponse;
import com.company.attendance.dto.response.FieldErrorDetail;
import com.company.attendance.enums.ErrorCode;
import com.company.attendance.security.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns every exception into the standard envelope. Nothing else in the
 * application writes an error body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @org.springframework.web.bind.annotation.ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Object>> handleBusiness(BusinessException e, HttpServletRequest request) {
        ErrorCode code = e.getErrorCode();
        // Expected failures are not stack-traced; they are the API working correctly.
        log.debug("Business rule rejected request {} {}: {} - {}",
                request.getMethod(), request.getRequestURI(), code, e.getMessage());

        return build(code, e.getMessage(), e.getDetails(), request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Object>> handleValidation(MethodArgumentNotValidException e,
                                                                HttpServletRequest request) {
        List<FieldErrorDetail> fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();

        Map<String, Object> details = new HashMap<>();
        details.put("fieldErrors", fieldErrors);

        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage(), details, request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleConstraintViolation(ConstraintViolationException e,
                                                                         HttpServletRequest request) {
        List<FieldErrorDetail> fieldErrors = e.getConstraintViolations().stream()
                .map(violation -> new FieldErrorDetail(
                        violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();

        Map<String, Object> details = new HashMap<>();
        details.put("fieldErrors", fieldErrors);

        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getDefaultMessage(), details, request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Object>> handleUnreadable(HttpMessageNotReadableException e,
                                                                 HttpServletRequest request) {
        return build(ErrorCode.MALFORMED_JSON, ErrorCode.MALFORMED_JSON.getDefaultMessage(), null, request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleBadParameter(Exception e, HttpServletRequest request) {
        return build(ErrorCode.INVALID_REQUEST, e.getMessage(), null, request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Object>> handleAccessDenied(AccessDeniedException e,
                                                                   HttpServletRequest request) {
        return build(ErrorCode.ACCESS_DENIED, ErrorCode.ACCESS_DENIED.getDefaultMessage(), null, request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Object>> handleBadCredentials(BadCredentialsException e,
                                                                     HttpServletRequest request) {
        return build(ErrorCode.INVALID_CREDENTIALS, ErrorCode.INVALID_CREDENTIALS.getDefaultMessage(), null, request);
    }

    /**
     * A unique-constraint violation is how concurrent punch-ins are rejected, so
     * this handler is load-bearing rather than a catch-all.
     */
    @org.springframework.web.bind.annotation.ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Object>> handleDataIntegrity(DataIntegrityViolationException e,
                                                                    HttpServletRequest request) {
        log.warn("Data integrity violation on {} {}: {}",
                request.getMethod(), request.getRequestURI(), e.getMostSpecificCause().getMessage());
        return build(ErrorCode.DUPLICATE_RESOURCE,
                "This record conflicts with one that already exists", null, request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiResponse<Object>> handleOptimisticLock(OptimisticLockingFailureException e,
                                                                     HttpServletRequest request) {
        return build(ErrorCode.CONCURRENT_MODIFICATION,
                ErrorCode.CONCURRENT_MODIFICATION.getDefaultMessage(), null, request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler({
            NoHandlerFoundException.class,
            HttpRequestMethodNotSupportedException.class
    })
    public ResponseEntity<ApiResponse<Object>> handleNotFound(Exception e, HttpServletRequest request) {
        return build(ErrorCode.RESOURCE_NOT_FOUND, "No endpoint for this request", null, request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleUnexpected(Exception e, HttpServletRequest request) {
        String traceId = traceId(request);
        // The client gets a generic message; the traceId is the link to this log line.
        log.error("Unhandled exception on {} {} [traceId={}]",
                request.getMethod(), request.getRequestURI(), traceId, e);

        ApiResponse<Object> body = ApiResponse.error(
                ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.getDefaultMessage(),
                null,
                request.getRequestURI(),
                traceId);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // -----------------------------------------------------------------

    private ResponseEntity<ApiResponse<Object>> build(ErrorCode code, String message,
                                                       Object details, HttpServletRequest request) {
        ApiResponse<Object> body = ApiResponse.error(
                code,
                message != null ? message : code.getDefaultMessage(),
                details,
                request.getRequestURI(),
                traceId(request));
        return ResponseEntity.status(code.getHttpStatus()).body(body);
    }

    private FieldErrorDetail toFieldError(FieldError error) {
        return new FieldErrorDetail(error.getField(), error.getDefaultMessage());
    }

    private String traceId(HttpServletRequest request) {
        Object traceId = request.getAttribute(RequestIdFilter.REQUEST_ATTRIBUTE);
        return traceId != null ? traceId.toString() : null;
    }
}
