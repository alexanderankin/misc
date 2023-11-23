package misc.ipdb;

import misc.ipdb.IpDbService.IpAddress;
import misc.ipdb.IpDbService.IpRange;
import misc.ipdb.IpDbService.IpSpace;
import misc.ipdb.util.DbFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.BiFunction;

class IpDbServiceITest {

    static IpDbService ipDbService;

    @BeforeAll
    static void beforeAll() {
        ipDbService = new IpDbService(DbFactory.INSTANCE);
        ipDbService.dbMigrator().migrate();
    }

    @BeforeEach
    void beforeEach() {
        ipDbService.jdbcTemplate.getJdbcTemplate().setDataSource(DbFactory.INSTANCE.dataSource());
    }

    @Test
    void test() {
        IpSpace space = ipDbService.create(new IpSpace(null, "space", null, null));
        System.out.println(space);
        IpSpace space1 = ipDbService.create(new IpSpace(null, "space1", null, null));
        System.out.println(space1);

        IpRange range2 = ipDbService.reserve(new IpRange(null, "range2", null, space, IpAddress.v4("10.0.0.64"), IpAddress.v4("10.0.0.128")));
        System.out.println(range2);

        IpRange range1 = ipDbService.reserve(new IpRange(null, "range1", null, space, IpAddress.v4("10.0.0.0"), IpAddress.v4("10.0.0.64")));
        System.out.println(range1);
    }


    @Test
    void test_findIpRange() {
        IpSpace space = ipDbService.create(new IpSpace(null, "space", null, null));
        ipDbService.reserve(new IpRange(null, "range", null, space, IpAddress.v4("10.0.0.4"), IpAddress.v4("10.0.0.8")));

        BiFunction<IpAddress, IpAddress, IpRange> f = (a, b) -> new IpRange(null, "range", null, space, a, b);
        int i = ipDbService.find(f.apply(IpAddress.v4("10.0.0.0"), IpAddress.v4("10.0.0.4")));
        int j = ipDbService.find(f.apply(IpAddress.v4("10.0.0.8"), IpAddress.v4("10.0.0.12")));

        System.out.println(i);
        System.out.println(j);
    }
}
