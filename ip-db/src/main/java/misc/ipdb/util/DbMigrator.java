package misc.ipdb.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

@Slf4j
@RequiredArgsConstructor
public class DbMigrator {
    private final DataSource dataSource;

    @SuppressWarnings("SqlSourceToSinkFlow")
    @SneakyThrows
    public void migrate() {
        try (Connection connection = dataSource.getConnection()) {
            int current;
            try {
                current = getMigration(connection);
            } catch (Exception e) {
                createMigrationTables(connection);
                current = getMigration(connection);
            }

            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    for (Migration value : Migration.values()) {
                        if (current >= value.ordinal()) {
                            log.info("skipping migration {} because db is at {}", value, current);
                            continue;
                        }
                        for (String query : value.getSql().split(";")) {
                            statement.execute(query);
                        }
                        statement.execute("insert into migrations(id) values(%d)".formatted(value.ordinal()));
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw new RuntimeException("rolling back transaction", e);
            }
        }
    }

    @SneakyThrows
    private void createMigrationTables(Connection connection) {
        connection.createStatement().execute("create table migrations(id int)");
        connection.createStatement().execute("insert into migrations(id) values(-1)");
    }

    @SneakyThrows
    private int getMigration(Connection connection) {
        ResultSet resultSet = connection.createStatement().executeQuery("select max(id) from migrations");
        resultSet.next();
        return resultSet.getInt(1);
    }

    public static void main(String[] args) {
        new DbMigrator(DbFactory.INSTANCE.dataSource()).migrate();
    }

    @SuppressWarnings("SqlDialectInspection")
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
                            version     smallint     not null,
                            min         numeric(39)  null, -- default max space
                            max         numeric(39)  null
                        )
                        """),

        IP_RANGES("create ip ranges",
                // language=sql
                """
                        create table ip_range_v4(
                            id          serial       primary key,
                            ip_space_id integer      not null references ip_space(id),
                            name        varchar(500) not null,
                            description varchar(500) null,
                            min         numeric(10)  not null,
                            max         numeric(10)  not null,
                            unique (ip_space_id, name)
                        );

                        create table ip_range_v6(
                            id          serial       primary key,
                            ip_space_id integer      not null references ip_space(id),
                            name        varchar(500) not null,
                            description varchar(500) null,
                            min         numeric(39)  not null,
                            max         numeric(39)  not null,
                            unique (ip_space_id, name)
                        )
                        """),
        ;

        final String description;
        final String sql;
    }
}
