package dev.flags.server.flag;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FlagConfigRepository extends JpaRepository<FlagConfig, UUID> {

    /** Every flag in every environment, in one query, for the list view. */
    @Query("select c from FlagConfig c join fetch c.flag f join fetch c.environment e order by f.key, e.sortOrder")
    List<FlagConfig> findAllWithFlagAndEnvironment();

    @Query("select c from FlagConfig c join fetch c.flag f join fetch c.environment e"
            + " where f.key = :flagKey order by e.sortOrder")
    List<FlagConfig> findByFlagKey(String flagKey);

    @Query("select c from FlagConfig c join fetch c.flag f join fetch c.environment e"
            + " where f.key = :flagKey and e.key = :environmentKey")
    Optional<FlagConfig> findByFlagKeyAndEnvironmentKey(String flagKey, String environmentKey);

    @Query("select c from FlagConfig c join fetch c.flag f where c.environment.id = :environmentId order by f.key")
    List<FlagConfig> findByEnvironmentId(UUID environmentId);
}
