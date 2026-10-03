package dev.flags.server.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Page<AuditEvent> findAllByOrderByIdDesc(Pageable pageable);

    Page<AuditEvent> findByTargetOrderByIdDesc(String target, Pageable pageable);
}
