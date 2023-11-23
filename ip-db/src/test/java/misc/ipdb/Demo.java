package misc.ipdb;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Demo {
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
