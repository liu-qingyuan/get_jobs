package com.getjobs.application.workbench;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkbenchLocalOnlyFilter extends OncePerRequestFilter {
    private static final Set<String> HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final Set<String> ADDRESSES = Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1");
    @Value("${server.port:9527}") private int backendPort = 9527;
    public static List<String> trustedOrigins(int backendPort) {
        return List.of("http://localhost:" + backendPort, "http://127.0.0.1:" + backendPort,
                "http://localhost:6866", "http://127.0.0.1:6866");
    }
    // Guard every local-app entry, including encoded paths and future private endpoints.
    // Matching raw URI prefixes would let the servlet's later decoding bypass the guard.
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        boolean allowed = ADDRESSES.contains(request.getRemoteAddr()) && HOSTS.contains(request.getServerName());
        String origin = request.getHeader("Origin");
        if (origin != null) allowed &= trustedOrigins(backendPort).contains(origin);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (!allowed) {
            response.setStatus(403); response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"个人资料工作台仅允许本机及可信本地页面访问\"}"); return;
        }
        chain.doFilter(request, response);
    }
}
