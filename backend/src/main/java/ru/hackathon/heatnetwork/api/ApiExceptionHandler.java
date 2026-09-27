package ru.hackathon.heatnetwork.api;

import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException exception) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.status);
        if (exception.status == 503) response.header("Retry-After", "5");
        return response.contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(exception.error);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> tooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(413).body(new ApiError("FILE_TOO_LARGE", "Размер файла или multipart-запроса превышает допустимый предел."));
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class, MultipartException.class})
    public ResponseEntity<ApiError> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_INPUT", "Ожидается multipart-запрос с непустым файлом в поле file."));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(415).body(new ApiError("INVALID_INPUT", "Используйте Content-Type multipart/form-data."));
    }

    @ExceptionHandler({IOException.class, RuntimeException.class})
    public ResponseEntity<ApiError> internal(Exception exception) {
        LOG.error("HTTP request failed", exception);
        return ResponseEntity.status(500).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(new ApiError("INTERNAL_ERROR", "Не удалось выполнить запрос из-за ошибки сервера."));
    }
}
