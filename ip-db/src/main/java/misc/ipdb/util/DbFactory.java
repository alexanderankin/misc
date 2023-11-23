package misc.ipdb.util;

import org.springframework.boot.jdbc.DataSourceBuilder;

import javax.sql.DataSource;

public class DbFactory {
    public static final DbFactory INSTANCE = new DbFactory();

    public DataSource dataSource() {
        return dataSource("jdbc:h2:mem:ipdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH");
    }

    public DataSource dataSource(String url) {
        return DataSourceBuilder.create()
                .url(url)
                .build();
    }
}
