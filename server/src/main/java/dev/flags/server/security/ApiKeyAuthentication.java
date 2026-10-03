package dev.flags.server.security;

import java.util.List;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    public static final String AUTHORITY = "SDK";

    private final ApiKeyPrincipal principal;

    public ApiKeyAuthentication(ApiKeyPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority(AUTHORITY)));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public ApiKeyPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public String getName() {
        return "api-key:" + principal.keyId();
    }
}
