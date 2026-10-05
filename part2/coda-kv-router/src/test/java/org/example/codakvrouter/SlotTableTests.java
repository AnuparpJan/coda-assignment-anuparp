package org.example.codakvrouter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.example.codakvrouter.routing.SlotTable;
import org.junit.jupiter.api.Test;

class SlotTableTests {

    @Test
    void ownershipDependsOnlyOnTheNodeSetNotItsOrder() {
        Map<String, String> a = new LinkedHashMap<>();
        a.put("node-1", "http://a"); a.put("node-2", "http://b"); a.put("node-3", "http://c");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("node-3", "http://c"); b.put("node-1", "http://a"); b.put("node-2", "http://b");
        SlotTable first = new SlotTable(a);
        SlotTable second = new SlotTable(b);
        for (int i = 0; i < 1000; i++) {
            assertEquals(first.ownerOf("key-" + i), second.ownerOf("key-" + i));
        }
    }

    @Test
    void slotsAreSplitEvenly() {
        SlotTable table = new SlotTable(Map.of("node-1", "x", "node-2", "x", "node-3", "x"));
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 30000; i++) {
            counts.merge(table.ownerOf("key-" + i), 1, Integer::sum);
        }
        counts.values().forEach(c -> assertTrue(c > 9000 && c < 11000, counts.toString()));
        assertTrue(SlotTable.slotFor("anything") >= 0 && SlotTable.slotFor("anything") < SlotTable.SLOT_COUNT);
    }

    @Test
    void refusesEmptyNodeList() {
        assertThrows(IllegalStateException.class, () -> new SlotTable(Map.of()));
    }
}
