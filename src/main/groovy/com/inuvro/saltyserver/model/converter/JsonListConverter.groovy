package com.inuvro.saltyserver.model.converter

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.type.CollectionType
import tools.jackson.databind.type.TypeFactory
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

// Base class for JSON list converters - not annotated as @Converter since it's generic
abstract class JsonListConverter<T> implements AttributeConverter<List<T>, String> {
    private static final ObjectMapper objectMapper = new ObjectMapper()
    private final Class<T> clazz

    JsonListConverter(Class<T> clazz) {
        this.clazz = clazz
    }

    @Override
    String convertToDatabaseColumn(List<T> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null
        }
        try {
            return objectMapper.writeValueAsString(attribute)
        } catch (Exception e) {
            throw new RuntimeException("Error converting list to JSON", e)
        }
    }

    @Override
    List<T> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return []
        }
        try {
            TypeFactory typeFactory = objectMapper.typeFactory
            CollectionType listType = typeFactory.constructCollectionType(List.class, clazz)
            return objectMapper.readValue(dbData, listType)
        } catch (Exception e) {
            throw new RuntimeException("Error converting JSON to list", e)
        }
    }
}
