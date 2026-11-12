package io.moneyflow.account.audit;

/** Значения audit_log.action. Дополняется по мере появления операций. */
public final class AuditActions {

    public static final String CREATE_ACCOUNT = "CREATE_ACCOUNT";
    public static final String DEPOSIT = "DEPOSIT";
    public static final String TRANSFER = "TRANSFER";

    private AuditActions() {
    }
}
