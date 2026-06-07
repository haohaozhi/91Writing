package app.dreamwriter.common;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
  public ResponseEntity<Map<String, Object>> handleBadRequest(RuntimeException error) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(error));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleUnexpected(Exception error) {
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(error));
  }

  private Map<String, Object> errorBody(Exception error) {
    return Map.of(
        "timestamp", Instant.now().toString(),
        "error", error.getClass().getSimpleName(),
        "message", error.getMessage() == null ? "未知错误" : error.getMessage()
    );
  }
}
