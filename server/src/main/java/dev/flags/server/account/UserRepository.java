package dev.flags.server.account;

import dev.flags.server.security.Role;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * None of the queries here, or in any other repository, mention a tenant. Postgres
 * applies that filter to every statement the application runs.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    List<User> findAllByOrderByCreatedAtAsc();

    long countByRole(Role role);
}
