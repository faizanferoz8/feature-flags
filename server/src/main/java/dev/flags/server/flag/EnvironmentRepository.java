package dev.flags.server.flag;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {

    List<Environment> findAllByOrderBySortOrderAsc();

    Optional<Environment> findByKey(String key);
}
