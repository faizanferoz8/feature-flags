package dev.flags.server.apikey;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    @Query("select k from ApiKey k join fetch k.environment e order by k.createdAt desc")
    List<ApiKey> findAllWithEnvironment();
}
