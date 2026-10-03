package dev.flags.server.security;

import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** What a member of a tenant may do. Each role includes everything the one below it can. */
public enum Role {
    VIEWER,
    EDITOR,
    ADMIN;

    /** The role's own authority plus those of every lesser role, so checks can name the minimum. */
    public List<GrantedAuthority> authorities() {
        return java.util.Arrays.stream(values())
                .filter(role -> role.ordinal() <= ordinal())
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
    }
}
