import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;

public class MapTest {
    public static void main(String[] args) {
        NavigableMap<Integer, Integer> map = new TreeMap<>();
        map.put(1, 1);
        map.put(2, 1);
        map.put(3, 1);
        map.put(100, 1);

        System.out.println(map.firstKey());
        System.out.println(map.lastKey());
        System.out.println(map.headMap(5));
        System.out.println(map.tailMap(5));
        System.out.println(map.subMap(2, 79));
        System.out.println(map.ceilingKey(30));
        System.out.println(map.floorKey(30));
    }
}
