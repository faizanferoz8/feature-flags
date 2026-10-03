package dev.flags.server.flag;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FlagRepository extends JpaRepository<Flag, UUID> {

    Optional<Flag> findByKey(String key);
}
