package io.moneyflow.account.audit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * MANDATORY: запись аудита обязана идти в одной транзакции с самой операцией,
     * чтобы не получить действие без записи в журнале или запись о несостоявшемся действии.
     *
     * @param actorId null означает системное действие (пока нет аутентификации, всегда null)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, String action, String entityType, UUID entityId, Map<String, Object> details) {
        repository.save(new AuditLog(actorId, action, entityType, entityId, details));
    }
}
