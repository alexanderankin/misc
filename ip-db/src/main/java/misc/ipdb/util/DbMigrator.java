package misc.ipdb.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@RequiredArgsConstructor
public class DbMigrator {
    private final DataSource dataSource;

    @SuppressWarnings("SqlSourceToSinkFlow")
    @SneakyThrows
    public void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    for (Migration value : Migration.values()) {
                        for (String query : value.getSql().split(";")) {
                            statement.  execute(query);
                        }
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw new RuntimeException("rolling back transaction", e);
            }
        }
    }

    public static void main(String[] args) {
        new DbMigrator(DbFactory.INSTANCE.dataSource()).migrate();
    }

    @RequiredArgsConstructor
    @Getter
    enum Migration {
        IP_SPACE("create ip space",
                // language=sql
                """
                        create table ip_space(
                            id          serial       primary key,
                            name        varchar(500) not null unique,
                            description varchar(500) null,
                            min         bytea        null, -- default max space
                            max         bytea        null
                        )
                        """),

        IP_RANGE("create ip range",
                // language=sql
                """
                        create table ip_range(
                            id          serial       primary key,
                            ip_space_id integer      not null references ip_space(id),
                            name        varchar(500) not null unique,
                            description varchar(500) null,
                            min         bytea        not null,
                            max         bytea        not null
                        )
                        """),
        ;

        final String description;
        final String sql;
    }
}
