package app.dreamwriter.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

  @Value("${dream-writer.request-log-enabled:false}")
  private boolean requestLogEnabled;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !requestLogEnabled;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain filterChain
  ) throws ServletException, IOException {
    long startedAt = System.currentTimeMillis();
    try {
      filterChain.doFilter(request, response);
    } finally {
      long elapsedMs = System.currentTimeMillis() - startedAt;
      String query = request.getQueryString();
      String path = query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
      log.info("HTTP {} {} -> {} ({} ms)", request.getMethod(), path, response.getStatus(), elapsedMs);
    }
  }
}
