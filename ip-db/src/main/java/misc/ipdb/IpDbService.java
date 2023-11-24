package misc.ipdb;

import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import misc.ipdb.util.DbFactory;
import misc.ipdb.util.DbMigrator;
import misc.ipdb.util.IpDataNotFoundException;
import misc.ipdb.util.IpRangeConflictsException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class IpDbService {
    final DataSource dataSource;
    final JdbcClient jdbcClient;

    public IpDbService(DbFactory dbFactory) {
        this(dbFactory.dataSource());
    }

    public IpDbService(DataSource dataSource) {
        this(dataSource, JdbcClient.create(dataSource));
    }

    public DbMigrator dbMigrator() {
        return new DbMigrator(dataSource);
    }

    public IpSpace create(IpSpace space) {
        var g = new GeneratedKeyHolder();
        jdbcClient.sql("""
                        insert into ip_space(name, description, version, min, max)\s
                        values(:name, :description, :version, :min, :max)
                        """)
                .paramSource(space)
                .update(g);
        return space.setId(Objects.requireNonNull(g.getKey()).intValue());
    }

    public IpSpace update(IpSpace space) {
        int updated = jdbcClient.sql("""
                        update ip_space\s
                        set name = :name,\s
                            description = :description,\s
                            version = :version,\s
                            min = :min,\s
                            max = :max\s
                        where id = :id
                        """)
                .paramSource(space)
                .update();
        return updated == 0 ? null : space;
    }

    public List<IpSpace> listSpaces(PageRequest pageRequest) {
        return jdbcClient.sql("select * from ip_space limit ? offset ?").params(pageRequest.getPageSize(), pageRequest.getOffset()).query(IpSpace.class).list();
    }

    public IpSpace findSpace(int id) {
        return jdbcClient.sql("select * from ip_space where id = ?").params(id).query(IpSpace.class).optional().orElse(null);
    }

    public IpSpace delete(IpSpace space) {
        int updated = jdbcClient.sql("delete from ip_space where id = ?")
                .param(Objects.requireNonNull(space.getId()))
                .update();
        return updated == 0 ? null : space;
    }

    public IpRange reserve(IpRange ipRange, String min, String max) {
        IpVersion ipVersion = lookupIpVersion(ipRange);
        switch (ipVersion) {
            case V4 -> ipRange.setMinFromIp(IpAddress.v4(min)).setMaxFromIp(IpAddress.v4(max));
            case V6 -> ipRange.setMinFromIp(IpAddress.v6(min)).setMaxFromIp(IpAddress.v6(max));
        }
        return reserve(ipRange, ipVersion);
    }

    public IpRange reserve(IpRange ipRange) {
        IpVersion ipVersion = lookupIpVersion(ipRange);
        return reserve(ipRange, ipVersion);
    }

    private IpRange reserve(IpRange ipRange, IpVersion ipVersion) {
        int count = find(ipVersion, ipRange);

        if (count > 0) {
            if (log.isTraceEnabled())
                log.trace("{}", findRanges(ipVersion, ipRange));
            throw new IpRangeConflictsException();
        }

        int v = ipVersion.getVersion();
        var g = new GeneratedKeyHolder();
        jdbcClient.sql("insert into ip_range_v" + v +
                        "(ip_space_id, name, description, min, max) " +
                        "values (:ipSpaceId, :name, :description, :min, :max)")
                .paramSource(ipRange)
                .update(g);

        int id = Objects.requireNonNull(g.getKey()).intValue();

        return ipRange.setId(id);
    }

    public IpRange findRange(int spaceId, int rangeId) {
        IpSpace ipSpace = jdbcClient.sql("select * from ip_space where id = ?").params(spaceId).query(IpSpace.class).optional().orElseThrow(IpDataNotFoundException::new);
        int v = ipSpace.getIpVersion().getVersion();
        IpRange ipRange = jdbcClient.sql("select * from ip_range_v" + v + " where id = ?").params(rangeId).query(IpRange.class).optional().orElseThrow(IpDataNotFoundException::new);
        ipRange.setIpSpace(ipSpace);
        return ipRange;
    }

    public List<IpRange> listRanges(int ipSpaceId, PageRequest pageRequest) {
        return listRanges(findSpace(ipSpaceId), pageRequest);
    }

    public List<IpRange> listRanges(IpSpace ipSpace, PageRequest pageRequest) {
        return jdbcClient
                .sql("select * from ip_range_v" + ipSpace.getIpVersion().getVersion() +
                        " where ip_space_id = ? " +
                        "order by min asc " +
                        "limit ? offset ?")
                .params(ipSpace.getId(), pageRequest.getPageSize(), pageRequest.getOffset())
                .query(IpRange.class)
                .stream()
                .peek(e -> e.setIpSpace(ipSpace))
                .toList();
    }

    public IpRange release(IpRange ipRange) {
        return jdbcClient.sql("delete from ip_range_v" + lookupIpVersion(ipRange).getVersion() + " where id = ?")
                .params(Objects.requireNonNull(ipRange.getId()))
                .update() == 0 ? null : ipRange;
    }

    // returns if this ip address is within any of the ranges (or not)
    public boolean free(IpSpace ipSpace, IpAddress ipAddress) {
        int v = ipSpace.getIpVersion().getVersion();
        return 0 == jdbcClient.sql("select count(*) from ip_range_v" + v + " " + """
                        where (min >= :value and max < :value)
                        and ip_space_id = :ip_space
                        """)
                .params(Map.of("value", ipAddress.toBigInteger(),
                        "ip_space", ipSpace.getId()))
                .query(Integer.class)
                .single();
    }

    // returns if any addresses in this range are within any of the ranges (or not)
    public boolean free(IpRange ipRange) {
        return 0 == find(lookupIpVersion(ipRange), ipRange);
    }

    // find list of ip ranges which contain addresses within this ip range
    public List<IpRange> foundWithin(IpRange ipRange) {
        return findRanges(lookupIpVersion(ipRange), ipRange);
    }

    // find the range containing this ip address (or null if not found)
    public IpRange rangeOf(int ipSpaceId, IpAddress ipAddress) {
        return rangeOf(findSpace(ipSpaceId), ipAddress);
    }

    // find the range containing this ip address (or null if not found)
    public IpRange rangeOf(IpSpace ipSpace, IpAddress ipAddress) {
        return jdbcClient.sql("select * from ip_range_v" + ipSpace.getIpVersion().getVersion() +
                        " where ip_space_id = :ip_space_id and min >= :value and max < :value")
                .params(Map.of("ip_space_id", ipSpace.getId(), "value", ipAddress.toBigInteger()))
                .query(IpRange.class)
                .optional().orElse(null);
    }

    private IpVersion lookupIpVersion(IpRange ipRange) {
        if (ipRange.getIpSpace() != null && ipRange.getIpSpace().getIpVersion() != null)
            return ipRange.getIpSpace().getIpVersion();
        IpVersion ipVersion = jdbcClient.sql("select version from ip_space where id = ?")
                .params(ipRange.getIpSpaceId())
                .query(Integer.class)
                .optional()
                .map(IpVersion::from)
                .orElseThrow(IpDataNotFoundException::new);
        if (ipRange.getIpSpace() == null)
            ipRange.setIpSpace(new IpSpace()
                    .setIpVersion(ipVersion)
                    .setId(ipRange.getIpSpaceId()));
        return ipVersion;
    }

    @SuppressWarnings({"SqlDialectInspection"})
    int find(IpVersion ipVersion, IpRange ipRange) {
        /*
            --------|_----1---|_----2---|_--------
            -----X--|_------X-|_--------|_-------- - find 1 - min is less than max, max is more than max
            --------|_--------|_-------X|_-X------ - find 2 - min is less than min, max is more than min
            --------|_--------|_----X-X-|_-------- - find 2 - min is less than min, max is more than min
            --------|_--------|_--------|x---x---- - find null - max is exclusive
            -----X--|X--------|_--------|_-------- - find 1 - min is inclusive
            --------|_--------|_--------|_---X---X - find null
         */
        return jdbcClient.sql("select count(*) from ip_range_v" + ipVersion.getVersion() + " where " +
                        "((min <= :min and max > :min) or (min < :max and max > :max)) " +
                        "and ip_space_id = :ip_space")
                .params(Map.of(
                        "ip_space", ipRange.getIpSpaceId(),
                        "min", ipRange.getMin(),
                        "max", ipRange.getMax()
                ))
                .query(Integer.class)
                .single();
    }

    List<IpRange> findRanges(IpVersion ipVersion, IpRange ipRange) {
        /*
            --------|_----1---|_----2---|_--------
            -----X--|_------X-|_--------|_-------- - find 1 - min is less than max, max is more than max
            --------|_--------|_-------X|_-X------ - find 2 - min is less than min, max is more than min
            --------|_--------|_----X-X-|_-------- - find 2 - min is less than min, max is more than min
            --------|_--------|_--------|x---x---- - find null - max is exclusive
            -----X--|X--------|_--------|_-------- - find 1 - min is inclusive
            --------|_--------|_--------|_---X---X - find null
         */

        return jdbcClient.sql("select * from ip_range_v" + ipVersion.getVersion() + " " +
                        """
                                where ((min <= :min and max > :min) or (min < :max and max > :max))
                                and ip_space_id = :ip_space
                                """)
                .params(Map.of(
                        "ip_space", ipRange.getIpSpaceId(),
                        "min", ipRange.getMin(),
                        "max", ipRange.getMax()
                ))
                .query(IpRange.class)
                .list();
    }

    public static void main(String[] args) {
        // System.out.println(Arrays.toString(IpAddress.parseIpV4("1.1.1.1")));
        // System.out.println(Arrays.toString(new BigInteger("16843009").toByteArray()));
        // System.out.println(Arrays.toString(IpAddress.parseIpV6("2001:0000:130F:0000:0000:09C0:876A:130B")));
        // System.out.println(new BigInteger(IpAddress.parseIpV6("2001:0000:130F:0000:0000:09C0:876A:130B")));
        // System.out.println(IpAddress.serializeIpV6(IpAddress.parseIpV6("2001:0000:130F:0000:0000:09C0:876A:130B")));
    }

    @Data
    @Accessors(chain = true)
    public static class IpSpace {
        Integer id;
        String name;
        String description;
        Integer version;
        transient IpVersion ipVersion;
        BigInteger min;
        BigInteger max;

        public IpVersion getIpVersion() {
            if (version == null) return null;
            if (ipVersion == null) ipVersion = IpVersion.from(version);
            return ipVersion;
        }

        public IpSpace setIpVersion(IpVersion ipVersion) {
            version = ipVersion.getVersion();
            this.ipVersion = ipVersion;
            return this;
        }
    }

    @Data
    @Accessors(chain = true)
    public static class IpRange {
        Integer id;
        Integer ipSpaceId;
        String name;
        String description;
        BigInteger min;
        BigInteger max;
        transient IpSpace ipSpace;

        public IpRange setMinFromIp(IpAddress ipAddress) {
            return setMin(ipAddress.toBigInteger());
        }

        public IpRange setMaxFromIp(IpAddress ipAddress) {
            return setMax(ipAddress.toBigInteger());
        }
    }

    @RequiredArgsConstructor
    @Getter
    public enum IpVersion {
        V4(4), V6(6),
        ;
        private static final Map<Integer, IpVersion> BY_VERSION =
                Arrays.stream(values())
                        .collect(Collectors.toMap(IpVersion::getVersion, Function.identity()));

        private final int version;

        public static IpVersion from(int version) {
            return BY_VERSION.get(version);
        }
    }

    public record IpAddress(String address, byte[] value, IpVersion version) {
        public BigInteger toBigInteger() {
            return new BigInteger(value);
        }

        public static IpAddress from(String value, IpVersion ipVersion) {
            return switch (ipVersion) {
                case V4 -> v4(value);
                case V6 -> v6(value);
            };
        }

        public static IpAddress from(BigInteger value, IpVersion ipVersion) {
            return switch (ipVersion) {
                case V4 -> v4(value);
                case V6 -> v6(value);
            };
        }

        public static IpAddress v4(BigInteger value) {
            byte[] byteArray = value.toByteArray();
            byte[] padded = new byte[4];
            Arrays.fill(padded, (byte) 0);
            System.arraycopy(byteArray, 0, padded, padded.length - byteArray.length, byteArray.length);
            return v4(padded);
        }

        public static IpAddress v4(String address) {
            return new IpAddress(address, parseIpV4(address), IpVersion.V4);
        }

        public static IpAddress v4(byte[] value) {
            return new IpAddress(serializeIpV4(value), value, IpVersion.V4);
        }

        public static IpAddress v6(BigInteger value) {
            byte[] byteArray = value.toByteArray();
            byte[] padded = new byte[16];
            Arrays.fill(padded, (byte) 0);
            System.arraycopy(byteArray, 0, padded, padded.length - byteArray.length, byteArray.length);
            return v6(padded);
        }

        public static IpAddress v6(String address) {
            return new IpAddress(address, parseIpV6(address), IpVersion.V6);
        }

        public static IpAddress v6(byte[] value) {
            return new IpAddress(serializeIpV6(value), value, IpVersion.V6);
        }

        static byte[] parseIpV4(String address) {
            var parts = address.split("\\.");
            if (parts.length != 4) throw new IllegalArgumentException("not an ipv4, needs 4 parts");
            var result = new byte[4];
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                result[i] = (byte) Integer.parseInt(part);
            }
            return result;
        }

        static byte[] parseIpV6(String address) {
            String[] groups = address.split(":");
            var result = new byte[16];
            for (int i = 0; i < groups.length; i++) {
                String group = groups[i];
                byte[] bytes = HexFormat.of().parseHex(group);
                System.arraycopy(bytes, 0, result, i * 2, 2);
            }
            return result;
        }

        static String serializeIpV4(byte[] address) {
            StringJoiner stringJoiner = new StringJoiner(".");
            for (byte b : address) {
                stringJoiner.add(String.valueOf(Byte.toUnsignedInt(b)));
            }
            return stringJoiner.toString();
        }

        static String serializeIpV6(byte[] address) {
            StringJoiner stringJoiner = new StringJoiner(":");
            byte[] tmp = new byte[2];
            for (int i = 0; i < address.length; i += 8) {
                for (int j = 0; j < 8; j += 2) {
                    System.arraycopy(address, i + j, tmp, 0, 2);
                    stringJoiner.add(HexFormat.of().formatHex(tmp));
                }
            }
            return stringJoiner.toString();
        }
    }
}
