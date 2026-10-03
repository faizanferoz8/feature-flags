package dev.flags.server.account;

import dev.flags.server.account.AccountDtos.AddMemberRequest;
import dev.flags.server.account.AccountDtos.EnvironmentView;
import dev.flags.server.account.AccountDtos.LoginRequest;
import dev.flags.server.account.AccountDtos.SessionView;
import dev.flags.server.account.AccountDtos.SignupRequest;
import dev.flags.server.account.AccountDtos.TokenResponse;
import dev.flags.server.account.AccountDtos.UserView;
import dev.flags.server.audit.AuditAction;
import dev.flags.server.audit.AuditService;
import dev.flags.server.flag.Environment;
import dev.flags.server.flag.EnvironmentRepository;
import dev.flags.server.security.AuthLookup;
import dev.flags.server.security.AuthLookup.UserCredentials;
import dev.flags.server.security.CurrentUser;
import dev.flags.server.security.Role;
import dev.flags.server.security.TokenService;
import dev.flags.server.tenancy.TenantContext;
import dev.flags.server.web.ApiException;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountService {

    private final AuthLookup lookup;
    private final UserRepository users;
    private final TenantRepository tenants;
    private final EnvironmentRepository environments;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuditService audit;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    /** Verified against when the email is unknown, so that case costs the same as a wrong password. */
    private final String decoyHash;

    AccountService(
            AuthLookup lookup,
            UserRepository users,
            TenantRepository tenants,
            EnvironmentRepository environments,
            PasswordEncoder passwords,
            TokenService tokens,
            AuditService audit,
            EntityManager entityManager,
            TransactionTemplate transactions) {
        this.lookup = lookup;
        this.users = users;
        this.tenants = tenants;
        this.environments = environments;
        this.passwords = passwords;
        this.tokens = tokens;
        this.audit = audit;
        this.entityManager = entityManager;
        this.transactions = transactions;
        this.decoyHash = passwords.encode(UUID.randomUUID().toString());
    }

    /**
     * Creates a tenant, its first administrator and its environments. The tenant's id is
     * chosen here, before anything is written, because the transaction has to announce a
     * tenant before row-level security will let it insert one.
     */
    public TokenResponse signup(SignupRequest request) {
        UUID tenantId = UUID.randomUUID();
        String email = normalise(request.email());
        String passwordHash = passwords.encode(request.password());
        try (TenantContext.Scope ignored = TenantContext.open(tenantId)) {
            User admin = transactions.execute(status -> {
                entityManager.persist(new Tenant(tenantId, request.organization().trim()));
                User user = saveUser(email, passwordHash, Role.ADMIN);
                environments.saveAll(Environment.defaults());
                audit.record(
                        new CurrentUser(user.getId(), tenantId, email, Role.ADMIN),
                        AuditAction.ORGANIZATION_CREATED,
                        request.organization().trim(),
                        null,
                        null,
                        null);
                return user;
            });
            return new TokenResponse(tokens.issue(admin.getId(), tenantId), session(admin));
        }
    }

    public TokenResponse login(LoginRequest request) {
        Optional<UserCredentials> found = lookup.findUser(normalise(request.email()));
        boolean matches = passwords.matches(
                request.password(), found.map(UserCredentials::passwordHash).orElse(decoyHash));
        if (found.isEmpty() || !matches) {
            throw ApiException.unauthorized("The email or password is incorrect.");
        }
        UserCredentials user = found.get();
        try (TenantContext.Scope ignored = TenantContext.open(user.tenantId())) {
            SessionView session = transactions.execute(
                    status -> session(users.findById(user.id()).orElseThrow()));
            return new TokenResponse(tokens.issue(user.id(), user.tenantId()), session);
        }
    }

    @Transactional(readOnly = true)
    public SessionView session(CurrentUser current) {
        return session(users.findById(current.id()).orElseThrow());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<UserView> members() {
        return users.findAllByOrderByCreatedAtAsc().stream().map(UserView::of).toList();
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public UserView addMember(AddMemberRequest request) {
        User user = saveUser(normalise(request.email()), passwords.encode(request.password()), request.role());
        audit.record(AuditAction.MEMBER_ADDED, user.getEmail(), null, null, Map.of("role", user.getRole()));
        return UserView.of(user);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public UserView changeRole(UUID userId, Role role) {
        lockTenant();
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("No such member."));
        if (user.getRole() == role) {
            return UserView.of(user);
        }
        if (user.getRole() == Role.ADMIN) {
            requireAnotherAdmin();
        }
        Role previous = user.getRole();
        user.changeRole(role);
        audit.record(
                AuditAction.MEMBER_ROLE_CHANGED, user.getEmail(), null, Map.of("role", previous), Map.of("role", role));
        return UserView.of(user);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void removeMember(UUID userId) {
        lockTenant();
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("No such member."));
        if (user.getRole() == Role.ADMIN) {
            requireAnotherAdmin();
        }
        users.delete(user);
        audit.record(AuditAction.MEMBER_REMOVED, user.getEmail(), null, Map.of("role", user.getRole()), null);
    }

    /**
     * Two administrators demoting each other at the same moment would each see the other
     * still in place and both succeed, leaving nobody able to manage the organization.
     * Holding the tenant's row lock makes the second one wait and then see the truth.
     */
    private void lockTenant() {
        tenants.lockById(TenantContext.require()).orElseThrow();
    }

    private void requireAnotherAdmin() {
        if (users.countByRole(Role.ADMIN) <= 1) {
            throw ApiException.conflict("An organization needs at least one administrator.");
        }
    }

    private User saveUser(String email, String passwordHash, Role role) {
        try {
            return users.saveAndFlush(new User(email, passwordHash, role));
        } catch (DataIntegrityViolationException e) {
            // The unique index spans every tenant; a query could not see another tenant's user.
            throw ApiException.conflict("That email address is already registered.");
        }
    }

    private SessionView session(User user) {
        Tenant tenant = tenants.findById(user.getTenantId()).orElseThrow();
        List<EnvironmentView> views = environments.findAllByOrderBySortOrderAsc().stream()
                .map(environment -> new EnvironmentView(environment.getKey(), environment.getName()))
                .toList();
        return new SessionView(UserView.of(user), tenant.getName(), views);
    }

    private static String normalise(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
