package com.filevault.common;

import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    public static Map<String, String> body(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> application(ApiException failure) {
        return ResponseEntity.status(failure.status()).body(body(failure.code(), failure.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class})
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(body("INVALID_REQUEST", "Check the request fields."));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> oversized() {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(body("FILE_TOO_LARGE", "The uploaded file exceeds the limit."));
    }

    @ExceptionHandler({IOException.class, org.springframework.dao.DataAccessException.class})
    ResponseEntity<Map<String, String>> internal(Exception failure) {
        LOG.warn("FileVault operation failed: {}", failure.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(body("INTERNAL_ERROR", "The operation could not be completed."));
    }
}
