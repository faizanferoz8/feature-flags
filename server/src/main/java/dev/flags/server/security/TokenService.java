package dev.flags.server.security;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Issues the console's access tokens. A token says who the user is and which tenant they
 * belong to. It deliberately does not say what they may do: the role is read from the
 * database on every request (see {@link UserAuthenticationConverter}), so demoting or
 * removing someone takes effect immediately instead of when their token expires.
 */
@Service
public class TokenService {

    static final String TENANT_CLAIM = "tid";
    static final String ISSUER = "feature-flags";

    private final JwtEncoder encoder;
    private final JwtProperties properties;
    private final Clock clock;

    TokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issue(UUID userId, UUID tenantId) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim(TENANT_CLAIM, tenantId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.ttl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
