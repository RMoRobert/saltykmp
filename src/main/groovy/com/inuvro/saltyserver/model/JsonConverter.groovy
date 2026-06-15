package com.inuvro.saltyserver.model

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.type.CollectionType
import tools.jackson.databind.type.TypeFactory

class JsonConverter {
    private static final ObjectMapper objectMapper = new ObjectMapper()

    static String toJson(Object obj) {
        if (obj == null) return null
        return objectMapper.writeValueAsString(obj)
    }

    static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return null
        return objectMapper.readValue(json, clazz)
    }

    static <T> List<T> fromJsonList(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return []
        TypeFactory typeFactory = objectMapper.typeFactory
        CollectionType listType = typeFactory.constructCollectionType(List.class, clazz)
        return objectMapper.readValue(json, listType)
    }
}
