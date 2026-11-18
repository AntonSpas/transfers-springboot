package io.moneyflow.transfer;

import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контекст поднимается, а Flyway-миграции применяются к настоящему PostgreSQL.
 * Требуется запущенный Docker. Наследует общую базу, поэтому не поднимает собственный контейнер.
 */
class TransferServiceApplicationTests extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextLoads() {
    }

    @Test
    void flywayCreatesV1Tables() {
        Integer tables = jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = 'public' and table_name in ('transfers', 'outbox_events')",
                Integer.class);
        assertThat(tables).isEqualTo(2);
    }
}
