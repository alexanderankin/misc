package misc.ipdb;

import lombok.SneakyThrows;
import misc.ipdb.util.DbFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public class Example {
    @SneakyThrows
    public static void main(String[] args) {
        DataSource dataSource = DbFactory.INSTANCE.dataSource();

        try (Connection connection = dataSource.getConnection()) {
            connection.createStatement().execute("""
                    create table ranges(
                        id serial,
                        name varchar(500) not null unique,
                        min int not null,
                        max int not null
                    );
                    """);
        }

        try (Connection connection = dataSource.getConnection()) {
            try (Statement statement = connection.createStatement()) {

                statement.execute("insert into ranges(name, min, max) values ('hi', 0, 128)");
                ResultSet resultSet = statement.executeQuery("select * from ranges");

                while (resultSet.next()) {
                    int columnCount = resultSet.getMetaData().getColumnCount();
                    for (int i = 0; i < columnCount; i++) {
                        System.out.println(resultSet.getString(i + 1));
                    }
                }
            }
        }
    }
}
