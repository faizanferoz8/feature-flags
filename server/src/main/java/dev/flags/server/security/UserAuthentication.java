package dev.flags.server.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/** A console user who presented a valid token and still exists. */
public class UserAuthentication extends AbstractAuthenticationToken {

    private final CurrentUser user;

    public UserAuthentication(CurrentUser user) {
        super(user.role().authorities());
        this.user = user;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public CurrentUser getPrincipal() {
        return user;
    }

    @Override
    public String getName() {
        return user.email();
    }
}
