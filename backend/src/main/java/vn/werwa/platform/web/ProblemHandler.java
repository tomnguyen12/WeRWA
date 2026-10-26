package vn.werwa.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Mọi lỗi của API trả {@code application/problem+json} có {@code code} (CLAUDE.md, quy ước API). Lỗi chuẩn của Spring MVC
 * được gắn mã trong {@link PlatformError}; lỗi không lường trước trả {@code INTERNAL_ERROR}, không lộ chi tiết.
 */
@RestControllerAdvice
public class ProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex, HttpServletRequest request) {
        return respond(ex.error(), ex.detail(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Lỗi không lường trước ở {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(PlatformError.INTERNAL_ERROR, null, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        PlatformError error = map(ex, status);
        String uri = request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
        String detail = error == PlatformError.VALIDATION_FAILED && body instanceof ProblemDetail p ? p.getDetail() : null;
        ProblemDetail problem = Problems.of(error, detail, uri);
        HttpHeaders out = new HttpHeaders();
        out.putAll(headers);
        out.setContentType(Problems.PROBLEM_JSON);
        return ResponseEntity.status(error.status()).headers(out).body(problem);
    }

    private static PlatformError map(Exception ex, HttpStatusCode status) {
        if (ex instanceof NoResourceFoundException) {
            return PlatformError.ROUTE_NOT_FOUND;
        }
        if (ex instanceof HttpRequestMethodNotSupportedException) {
            return PlatformError.METHOD_NOT_ALLOWED;
        }
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return PlatformError.UNSUPPORTED_MEDIA_TYPE;
        }
        if (ex instanceof HttpMediaTypeNotAcceptableException) {
            return PlatformError.NOT_ACCEPTABLE;
        }
        if (ex instanceof HttpMessageNotReadableException) {
            return PlatformError.MALFORMED_REQUEST;
        }
        if (status.is4xxClientError()) {
            return PlatformError.VALIDATION_FAILED;
        }
        return PlatformError.INTERNAL_ERROR;
    }

    private static ResponseEntity<ProblemDetail> respond(ErrorCode error, String detail, HttpServletRequest request) {
        return ResponseEntity.status(error.status())
                .contentType(Problems.PROBLEM_JSON)
                .body(Problems.of(error, detail, request.getRequestURI()));
    }
}
