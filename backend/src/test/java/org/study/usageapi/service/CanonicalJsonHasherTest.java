package org.study.usageapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class CanonicalJsonHasherTest {

    @Test
    void hashesJsonObjectsIndependentOfPropertyOrder() {
        var mapper = new ObjectMapper();
        var hasher = new CanonicalJsonHasher(mapper);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("z", 1);
        first.put("a", Map.of("y", 2, "b", 3));
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("a", Map.of("b", 3, "y", 2));
        second.put("z", 1);

        assertArrayEquals(hasher.hash(first), hasher.hash(second));
    }

}
