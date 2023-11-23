package misc.ipdb;

import lombok.*;
import misc.ipdb.util.DbFactory;
import misc.ipdb.util.DbMigrator;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import java.math.BigInteger;
import java.util.*;

@RequiredArgsConstructor
public class IpDbService {
    final NamedParameterJdbcTemplate jdbcTemplate;
    SimpleJdbcInsert ipSpace;
    SimpleJdbcInsert ipRange;

    public IpDbService(DbFactory dbFactory) {
        this(new NamedParameterJdbcTemplate(dbFactory.dataSource()));
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
        int count = find(ipRange);

        if (count > 0) throw new IllegalArgumentException("conflicts");

        int id = getIpRangeInsert().execute(new BeanPropertySqlParameterSource(ipRange.toEntity()));

        return new IpRange(id, ipRange.name, ipRange.description, ipRange.ipSpace, ipRange.minInclusive, ipRange.maxExclusive);
    }

    @SuppressWarnings({"SqlDialectInspection", "DataFlowIssue"})
    int find(IpRange ipRange) {
        /*
            --------|_----1---|_----2---|_--------
            -----X--|_------X-|_--------|_-------- - find 1 - min is less than max, max is more than max
            --------|_--------|_-------X|_-X------ - find 2 - min is less than min, max is more than min
            --------|_--------|_----X-X-|_-------- - find 2 - min is less than min, max is more than min
            --------|_--------|_--------|x---x---- - find null - max is exclusive
            -----X--|X--------|_--------|_-------- - find 1 - min is inclusive
            --------|_--------|_--------|_---X---X - find null
         */
        return jdbcTemplate.queryForObject("""
                        select count(*) from ip_range
                        where
                            (min <= :min and max > :min) or
                            (min <= :max and max > :max)
                        """,
                Map.of(
                        "min", ipRange.minInclusive().value(),
                        "max", ipRange.maxExclusive().value()
                ),
                Integer.class
        );
    }

    static public class Demo {
        final List<Map.Entry<Integer, Integer>> ranges = new ArrayList<>();
        void add(Map.Entry<Integer, Integer> range) {
            boolean conflicts = isConflicts(range);
            if (!conflicts) ranges.add(range);
        }

        private boolean isConflicts(Map.Entry<Integer, Integer> range) {
            return ranges.stream().anyMatch(each -> {
                boolean b = (each.getKey() <= range.getKey() && each.getValue() > range.getKey()) ||
                        (each.getKey() <= range.getValue() && each.getValue() > range.getValue());

                return b;
            });
        }

        public static void main(String[] args) {
            var demo = new Demo();
            demo.add(Map.entry(3, 5));
            System.out.println(demo.isConflicts(Map.entry(1, 2)));
            System.out.println(demo.isConflicts(Map.entry(1, 3)));
            System.out.println(demo.isConflicts(Map.entry(5, 6)));
            System.out.println(demo.isConflicts(Map.entry(4, 6)));
        }
    }

    private SimpleJdbcInsert getIpRangeInsert() {
        if (ipRange == null)
            ipRange = new SimpleJdbcInsert(jdbcTemplate.getJdbcTemplate())
                    .withTableName("ip_range")
                    .usingGeneratedKeyColumns("id");
        return ipRange;
    }

    public IpRange delete(IpSpace ipSpace, IpRange ipRange) {
        throw new UnsupportedOperationException();
    }

    public static void main(String[] args) {
        Demo.main(args);
        // System.out.println(Arrays.toString(IpAddress.parseIpV4("1.1.1.1")));
        // System.out.println(Arrays.toString(new BigInteger("16843009").toByteArray()));
        // System.out.println(Arrays.toString(IpAddress.parseIpV6("2001:0000:130F:0000:0000:09C0:876A:130B")));
        // System.out.println(new BigInteger(IpAddress.parseIpV6("2001:0000:130F:0000:0000:09C0:876A:130B")));
        // System.out.println(IpAddress.serializeIpV6(IpAddress.parseIpV6("2001:0000:130F:0000:0000:09C0:876A:130B")));
    }

    public IpSpace create(IpSpace space) {
        int id = getIpSpaceInsert().execute(new BeanPropertySqlParameterSource(space.toEntity()));
        return new IpSpace(id, space.name, space.description, space.totalRange);
    }

    private SimpleJdbcInsert getIpSpaceInsert() {
        if (ipSpace == null)
            ipSpace = new SimpleJdbcInsert(jdbcTemplate.getJdbcTemplate())
                    .withTableName("ip_space")
                    .usingGeneratedKeyColumns("id");
        return ipSpace;
    }

    public IpSpace delete(IpSpace space) {
        int updated = jdbcTemplate.getJdbcTemplate().update("delete from ip_space where id = ?", Objects.requireNonNull(space.id()));
        return updated == 0 ? null : space;
    }

    public record IpSpace(Integer id, String name, String description, IpRange totalRange) {

        Entity toEntity() {
            return new Entity(
                    null,
                    name(),
                    description(),
                    Optional.ofNullable(totalRange()).map(IpRange::minInclusive).map(IpAddress::value).orElse(null),
                    Optional.ofNullable(totalRange()).map(IpRange::maxExclusive).map(IpAddress::value).orElse(null)
            );
        }

        @Value
        static class Entity {
            Integer id;
            String name;
            String description;
            byte[] max;
            byte[] min;
        }
    }

    public record IpRange(Integer id, String name, String description, IpSpace ipSpace, IpAddress minInclusive,
                          IpAddress maxExclusive) {
        Entity toEntity() {
            return new IpRange.Entity(
                    id,
                    Optional.ofNullable(ipSpace()).map(IpSpace::id).orElse(null),
                    name(),
                    description(),
                    Optional.ofNullable(minInclusive()).map(IpAddress::value).orElse(null),
                    Optional.ofNullable(maxExclusive()).map(IpAddress::value).orElse(null)
            );
        }

        @Data
        @AllArgsConstructor
        @NoArgsConstructor
        static class Entity {
            Integer id;
            Integer ipSpaceId;
            String name;
            String description;
            byte[] min;
            byte[] max;
        }
    }

    public enum IpVersion {V4, V6}

    public record IpAddress(String address, byte[] value, IpVersion version) {
        public static IpAddress v4(String address) {
            return new IpAddress(address, parseIpV4(address), IpVersion.V4);
        }

        public static IpAddress v4(byte[] value) {
            return new IpAddress(serializeIpV4(value), value, IpVersion.V4);
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
