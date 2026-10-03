package dev.flags.server.audit;

import dev.flags.server.audit.AuditService.AuditPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/audit")
class AuditController {

    private final AuditService audit;

    AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    AuditPage list(
            @RequestParam(required = false) String target,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return audit.list(target, page, size);
    }
}
