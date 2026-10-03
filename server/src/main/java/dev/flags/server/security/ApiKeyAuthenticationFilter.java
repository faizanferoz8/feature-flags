package dev.flags.server.security;

import dev.flags.server.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates SDK requests by API key and binds the request to the key's tenant. A
 * request without a usable key carries on unauthenticated and is turned away by the
 * authorization rules.
 */
class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String SCHEME = "Bearer ";

    private final AuthLookup lookup;

    ApiKeyAuthenticationFilter(AuthLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<ApiKeyPrincipal> principal = presentedKey(request).flatMap(key -> lookup.findApiKey(ApiKeys.hash(key)));
        if (principal.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ApiKeyAuthentication(principal.get()));
        SecurityContextHolder.setContext(context);
        try (TenantContext.Scope ignored = TenantContext.open(principal.get().tenantId())) {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static Optional<String> presentedKey(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(SCHEME)) {
            return Optional.empty();
        }
        String key = header.substring(SCHEME.length()).trim();
        return key.startsWith(ApiKeys.PREFIX) ? Optional.of(key) : Optional.empty();
    }
}
