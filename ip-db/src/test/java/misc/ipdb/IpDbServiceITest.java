package misc.ipdb;

import lombok.extern.slf4j.Slf4j;
import misc.ipdb.IpDbService.IpAddress;
import misc.ipdb.IpDbService.IpRange;
import misc.ipdb.IpDbService.IpSpace;
import misc.ipdb.IpDbService.IpVersion;
import misc.ipdb.util.DbFactory;
import misc.ipdb.util.IpRangeConflictsException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.IntStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Slf4j
class IpDbServiceITest {

    static IpDbService ipDbService;

    @BeforeAll
    static void beforeAll() {
        ipDbService = new IpDbService(DbFactory.INSTANCE);
        ipDbService.dbMigrator().migrate();
    }

    @Test
    void test_basicUsage() {
        IpSpace space = ipDbService.create(new IpSpace().setId(null).setName("space").setDescription(null).setVersion(IpVersion.V4.getVersion()));
        log.debug("{}", space);
        IpSpace space1 = ipDbService.create(new IpSpace().setId(null).setName("space1").setDescription(null).setVersion(IpVersion.V4.getVersion()));
        log.debug("{}", space1);

        IpRange range1 = new IpRange().setName("range1").setIpSpaceId(space.getId()).setMinFromIp(IpAddress.v4("10.0.0.0")).setMaxFromIp(IpAddress.v4("10.0.0.64"));
        log.debug("{}", range1);
        range1 = ipDbService.reserve(range1);

        IpRange range2 = new IpRange().setName("range2").setIpSpaceId(space.getId()).setMinFromIp(IpAddress.v4("10.0.0.64")).setMaxFromIp(IpAddress.v4("10.0.0.128"));
        log.debug("{}", range2);
        range2 = ipDbService.reserve(range2);
    }

    static List<Arguments> testCases() {
        return List.of(
                Arguments.of(
                        new IpSpace()
                                .setName("testCases_1")
                                .setIpVersion(IpVersion.V4),
                        // setup
                        List.of(
                                Map.entry("10.0.0.0", "10.0.0.4"),
                                Map.entry("10.0.0.4", "10.0.0.8")
                        ),
                        // succeeds
                        List.of(
                                Map.entry("9.0.0.0", "10.0.0.0"),
                                Map.entry("10.0.0.8", "10.0.0.9"),
                                Map.entry("10.0.0.12", "10.0.0.16"),
                                Map.entry("10.0.0.32", "10.0.0.36")
                        ),
                        // fails
                        List.of(
                                Map.entry("8.0.0.0", "9.0.0.1"),
                                Map.entry("10.0.0.0", "11.0.0.0"),
                                Map.entry("10.0.0.0", "10.0.0.4"),
                                Map.entry("10.0.0.8", "10.0.0.10"),
                                Map.entry("10.0.0.32", "10.0.0.36")
                        )
                )
        );
    }

    @ParameterizedTest
    @MethodSource("testCases")
    void test_conflicts(IpSpace ipSpace,
                        List<Map.Entry<String, String>> setup,
                        List<Map.Entry<String, String>> succeeds,
                        List<Map.Entry<String, String>> fails) {
        ipDbService.create(ipSpace);
        log.debug("create ip space is " + ipSpace.getId());
        List<IpRange> setupRanges = IntStream.range(0, setup.size()).mapToObj(i -> range("setup" + i, setup.get(i), ipSpace)).toList();
        List<IpRange> succeedsRanges = IntStream.range(0, succeeds.size()).mapToObj(i -> range("succeeds" + i, succeeds.get(i), ipSpace)).toList();
        List<IpRange> failsRanges = IntStream.range(0, fails.size()).mapToObj(i -> range("faills" + i, fails.get(i), ipSpace)).toList();

        setupRanges.forEach(ipDbService::reserve);
        succeedsRanges.forEach(ipDbService::reserve);
        failsRanges.forEach(failsRange -> assertThrows(IpRangeConflictsException.class, () -> ipDbService.reserve(failsRange)));
    }

    IpRange range(String name, Map.Entry<String, String> minMax, IpSpace ipSpace) {
        return new IpRange().setName(name).setIpSpaceId(ipSpace.getId())
                .setMinFromIp(IpAddress.from(minMax.getKey(), ipSpace.getIpVersion()))
                .setMaxFromIp(IpAddress.from(minMax.getValue(), ipSpace.getIpVersion()))
                ;
    }

    @Test
    void test_findIpRange() {
        IpSpace ipSpace = ipDbService.create(new IpSpace().setName("space_test_findIpRange").setIpVersion(IpVersion.V4));
        log.debug("create ip space is " + ipSpace.getId());
        ipDbService.reserve(new IpRange().setName("range").setIpSpaceId(ipSpace.getId()).setMinFromIp(IpAddress.v4("10.0.0.4")).setMaxFromIp(IpAddress.v4("10.0.0.8")));

        BiFunction<IpAddress, IpAddress, IpRange> f = (a, b) -> new IpRange().setName("range").setIpSpaceId(ipSpace.getId()).setMinFromIp(a).setMaxFromIp(b);
        int i = ipDbService.find(ipSpace.getIpVersion(), f.apply(IpAddress.v4("10.0.0.0"), IpAddress.v4("10.0.0.4")));
        int j = ipDbService.find(ipSpace.getIpVersion(), f.apply(IpAddress.v4("10.0.0.8"), IpAddress.v4("10.0.0.12")));

        log.debug("i: {}", i);
        log.debug("j: {}", j);
    }

    @Test
    void test_ipSpaceCrud() {
        var name = "test_ipSpaceCrud.1";
        var space = ipDbService.create(new IpSpace().setName(name).setIpVersion(IpVersion.V6));
        assertThat(space.getId(), is(notNullValue()));
        assertThrows(DataIntegrityViolationException.class, () -> ipDbService.create(new IpSpace().setName(name)));

        List<IpSpace> ipSpaces = ipDbService.listSpaces(PageRequest.of(0, 500));
        assertThat(ipSpaces.stream().map(IpSpace::getName).toList(), hasItem(name));

        var spaceFromDb = ipDbService.findSpace(space.getId());
        assertThat(spaceFromDb, is(notNullValue()));
        assertThat(spaceFromDb.getName(), is(name));

        IpSpace deleted = ipDbService.delete(space);
        assertThat(deleted, is(notNullValue()));

        IpSpace deletedAgain = ipDbService.delete(space);
        assertThat(deletedAgain, is(nullValue()));

        IpSpace spaceFromDbAfterDelete = ipDbService.findSpace(space.getId());
        assertThat(spaceFromDbAfterDelete, is(nullValue()));
    }

    @Test
    void test_ipRangeCrud() {
        var name = "test_ipRangeCrud";
        var space = ipDbService.create(new IpSpace().setName(name + ".space").setIpVersion(IpVersion.V4));
        assertThat(space, is(notNullValue()));

        IpRange ipRange1 = ipDbService.reserve(new IpRange()
                .setName(name + ".1")
                .setIpSpaceId(space.getId())
                .setMinFromIp(IpAddress.v4("0.0.0.0"))
                .setMaxFromIp(IpAddress.v4("0.0.0.4")));

        IpRange ipRange2 = ipDbService.reserve(new IpRange()
                .setName(name + ".2")
                .setIpSpaceId(space.getId())
                .setMinFromIp(IpAddress.v4("0.0.1.0"))
                .setMaxFromIp(IpAddress.v4("0.0.1.4")));

        var ipRanges = ipDbService.listRanges(space, PageRequest.of(0, 10));
        assertThat(ipRanges, hasSize(2));
        assertThat(ipRanges.stream().map(IpRange::getMin)
                        .map(IpAddress::v4).map(IpAddress::address)
                        .toList(),
                contains("0.0.0.0", "0.0.1.0"));
        assertThat(ipRanges.stream().map(IpRange::getMax)
                        .map(IpAddress::v4).map(IpAddress::address)
                        .toList(),
                contains("0.0.0.4", "0.0.1.4"));
        assertThat(ipRanges, contains(ipRange1, ipRange2));

        assertThat(ipDbService.release(ipRange2), is(notNullValue()));
        assertThat(ipDbService.listRanges(space, PageRequest.of(0, 10)), contains(ipRange1));
        assertThat(ipDbService.release(ipRange2), is(nullValue()));

        assertThrows(DataIntegrityViolationException.class, () -> ipDbService.delete(space));

        assertThat(ipDbService.release(ipRange1), is(notNullValue()));
        assertThat(ipDbService.release(ipRange1), is(nullValue()));
        assertThat(ipDbService.listRanges(space, PageRequest.of(0, 10)), is(empty()));
    }
}
