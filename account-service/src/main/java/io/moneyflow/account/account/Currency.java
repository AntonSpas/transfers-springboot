package io.moneyflow.account.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Справочник валют (сидируется миграцией V2), только для чтения. */
@Entity
@Immutable
@Table(name = "currencies")
public class Currency {

    // CHAR(3) в БД: без SqlTypes.CHAR валидация схемы (ddl-auto: validate) ждала бы varchar.
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 3)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "minor_units", nullable = false)
    private short minorUnits;

    protected Currency() {
        // для JPA
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public short getMinorUnits() {
        return minorUnits;
    }
}
