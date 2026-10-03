package dev.flags.server.security;

import dev.flags.server.account.UserRepository;
import dev.flags.server.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns a verified token into the user it names, as that user is right now. A valid
 * signature proves who signed in; it does not prove they still have an account, or the
 * role they had when the token was issued. One primary-key read settles both.
 */
@Component
class UserAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository users;
    private final TransactionTemplate transactions;

    UserAuthenticationConverter(UserRepository users, TransactionTemplate transactions) {
        this.users = users;
        this.transactions = transactions;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId;
        UUID tenantId;
        try {
            userId = UUID.fromString(jwt.getSubject());
            tenantId = UUID.fromString(jwt.getClaimAsString(TokenService.TENANT_CLAIM));
        } catch (RuntimeException e) {
            throw new InvalidBearerTokenException("The token does not identify a user");
        }
        return TenantContext.callAs(tenantId, () -> transactions.execute(status -> users.findById(userId)))
                .map(user -> new UserAuthentication(
                        new CurrentUser(user.getId(), user.getTenantId(), user.getEmail(), user.getRole())))
                .orElseThrow(() -> new InvalidBearerTokenException("The user no longer exists"));
    }
}
