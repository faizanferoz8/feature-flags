package dev.flags.server.flag;

import dev.flags.core.Rule;
import dev.flags.server.web.Json;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;
import tools.jackson.core.type.TypeReference;

/** Stores a flag's targeting rules as one JSON document, in the order they are evaluated. */
@Converter
class RulesConverter implements AttributeConverter<List<Rule>, String> {

    private static final TypeReference<List<Rule>> RULES = new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<Rule> rules) {
        return Json.STORED.writeValueAsString(rules == null ? List.of() : rules);
    }

    @Override
    public List<Rule> convertToEntityAttribute(String json) {
        return json == null ? List.of() : Json.STORED.readValue(json, RULES);
    }
}
