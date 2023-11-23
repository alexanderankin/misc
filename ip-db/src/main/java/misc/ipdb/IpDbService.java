package misc.ipdb;

import lombok.*;
import lombok.experimental.Accessors;
import misc.ipdb.util.DbFactory;
import misc.ipdb.util.DbMigrator;
import misc.ipdb.util.IpRangeConflictsException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class IpDbService {
    private static final ColumnMapRowMapper COLUMN_MAP_ROW_MAPPER = new ColumnMapRowMapper();
    final NamedParameterJdbcTemplate jdbcTemplate;
    final JdbcClient jdbcClient;
    SimpleJdbcInsert ipSpace;
    SimpleJdbcInsert ipRangeV4;
    SimpleJdbcInsert ipRangeV6;

    public IpDbService(DbFactory dbFactory) {
        this(new NamedParameterJdbcTemplate(dbFactory.dataSource()));
    }

    IpDbService(NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this(namedParameterJdbcTemplate, JdbcClient.create(namedParameterJdbcTemplate));
    }

    public DbMigrator dbMigrator() {
        return new DbMigrator(jdbcTemplate.getJdbcTemplate().getDataSource());
    }

    // returns if this ip address is within any of the ranges (or not)
    public boolean free(IpAddress ipAddress) {
        throw new UnsupportedOperationException();
    }

    // returns if any addresses in this range are within any of the ranges (or not)
    public boolean free(IpRange ipRange) {
        throw new UnsupportedOperationException();
    }

    // find list of ip ranges which contain addresses within this ip range
    public List<IpRange> foundWithin(IpRange ipRange) {
        throw new UnsupportedOperationException();
    }

    // find the range containing this ip address (or null if not found)
    public IpRange rangeOf(IpAddress ipAddress) {
        throw new UnsupportedOperationException();
    }

    public IpRange reserve(IpRange ipRange) {
        IpVersion ipVersion;
        {
            IpSpace space = jdbcTemplate.getJdbcTemplate().queryForObject("select version from ip_space where id = ?",
                    new BeanPropertyRowMapper<>(IpSpace.class),
                    ipRange.getIpSpaceId());

            Objects.requireNonNull(space);
            Objects.requireNonNull(space.getVersion());
            ipVersion = space.getIpVersion();
        }
        int count = find(ipVersion, ipRange);

        if (count > 0) {
            System.out.println(findRanges(ipVersion, ipRange));
            throw new IpRangeConflictsException();
        }

        int id = (int) (switch (ipVersion) {
            case V4 -> getIpRangeInsertV4();
            case V6 -> getIpRangeInsertV6();
        }).executeAndReturnKey(new BeanPropertySqlParameterSource(ipRange));

        return ipRange.setId(id);
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

    private SimpleJdbcInsert getIpRangeInsertV4() {
        if (ipRangeV4 == null)
            ipRangeV4 = new SimpleJdbcInsert(jdbcTemplate.getJdbcTemplate())
                    .withTableName("ip_range_v4")
                    .usingGeneratedKeyColumns("id");
        return ipRangeV4;
    }

    private SimpleJdbcInsert getIpRangeInsertV6() {
        if (ipRangeV6 == null)
            ipRangeV6 = new SimpleJdbcInsert(jdbcTemplate.getJdbcTemplate())
                    .withTableName("ip_range_v6")
                    .usingGeneratedKeyColumns("id");
        return ipRangeV6;
    }

    public IpRange delete(IpSpace ipSpace, IpRange ipRange) {
        throw new UnsupportedOperationException();
    }

    public static void main(String[] args) {
        // System.out.println(Arrays.toString(IpAddress.parseIpV4("1.1.1.1")));
        // System.out.println(Arrays.toString(new BigInteger("16843009").toByteArray()));
        // System.out.println(Arrays.toString(IpAddress.parseIpV6("2001:0000:130F:0000:0000:09C0:876A:130B")));
        // System.out.println(new BigInteger(IpAddress.parseIpV6("2001:0000:130F:0000:0000:09C0:876A:130B")));
        // System.out.println(IpAddress.serializeIpV6(IpAddress.parseIpV6("2001:0000:130F:0000:0000:09C0:876A:130B")));
    }

    public IpSpace create(IpSpace space) {
        int id = (int) getIpSpaceInsert().executeAndReturnKey(new BeanPropertySqlParameterSource(space));
        space.setId(id);
        return space;
    }

    private SimpleJdbcInsert getIpSpaceInsert() {
        if (ipSpace == null)
            ipSpace = new SimpleJdbcInsert(jdbcTemplate.getJdbcTemplate())
                    .withTableName("ip_space")
                    .usingGeneratedKeyColumns("id");
        return ipSpace;
    }

    public IpSpace delete(IpSpace space) {
        int updated = jdbcTemplate.getJdbcTemplate().update("delete from ip_space where id = ?", Objects.requireNonNull(space.getId()));
        return updated == 0 ? null : space;
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
        BigInteger toBigInteger() {
            return new BigInteger(value);
        }

        public static IpAddress from(String value, IpVersion ipVersion) {
            return switch (ipVersion) {
                case V4 -> v4(value);
                case V6 -> v6(value);
            };
        }

        public static IpAddress v4(BigInteger value) {
            return v4(value.toByteArray());
        }

        public static IpAddress v4(String address) {
            return new IpAddress(address, parseIpV4(address), IpVersion.V4);
        }

        public static IpAddress v4(byte[] value) {
            return new IpAddress(serializeIpV4(value), value, IpVersion.V4);
        }

        public static IpAddress v6(BigInteger value) {
            return v6(value.toByteArray());
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
                stringJoiner.add(String.valueOf(b));
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
