package org.study.usageapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeMap;

@Component
public class CanonicalJsonHasher {

    private final ObjectMapper objectMapper;

    public CanonicalJsonHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] hash(Object value) {
        try {
            JsonNode tree = objectMapper.valueToTree(value);
            return MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(sortRecursively(tree)));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash request payload", exception);
        }
    }

    private JsonNode sortRecursively(JsonNode node) {
        if (node.isObject()) {
            var sorted = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), sortRecursively(entry.getValue())));
            ObjectNode result = objectMapper.createObjectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            var result = objectMapper.createArrayNode();
            node.forEach(child -> result.add(sortRecursively(child)));
            return result;
        }
        return node;
    }
}
