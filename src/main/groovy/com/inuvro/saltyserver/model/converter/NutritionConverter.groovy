package com.inuvro.saltyserver.model.converter

import tools.jackson.databind.ObjectMapper
import com.inuvro.saltyserver.model.NutritionInformation
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter(autoApply = true)
class NutritionConverter implements AttributeConverter<NutritionInformation, String> {
    private static final ObjectMapper objectMapper = new ObjectMapper()

    @Override
    String convertToDatabaseColumn(NutritionInformation attribute) {
        if (attribute == null) {
            return null
        }
        try {
            return objectMapper.writeValueAsString(attribute)
        } catch (Exception e) {
            throw new RuntimeException("Error converting NutritionInformation to JSON", e)
        }
    }

    @Override
    NutritionInformation convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isEmpty()) {
            return null
        }
        try {
            return objectMapper.readValue(dbData, NutritionInformation.class)
        } catch (Exception e) {
            throw new RuntimeException("Error converting JSON to NutritionInformation", e)
        }
    }
}
