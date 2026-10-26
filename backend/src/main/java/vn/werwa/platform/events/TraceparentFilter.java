package vn.werwa.platform.events;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/** Nhận header {@code traceparent} (W3C) của request để sự kiện phát ra trong request mang tiếp vết. */
public class TraceparentFilter extends OncePerRequestFilter {

    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("traceparent");
        if (header == null || !TRACEPARENT.matcher(header).matches()) {
            chain.doFilter(request, response);
            return;
        }
        try {
            TraceContext.with(header, () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new WrappedException(e);
                }
            });
        } catch (WrappedException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw (ServletException) e.getCause();
        }
    }

    private static final class WrappedException extends RuntimeException {
        WrappedException(Exception cause) {
            super(cause);
        }
    }
}
