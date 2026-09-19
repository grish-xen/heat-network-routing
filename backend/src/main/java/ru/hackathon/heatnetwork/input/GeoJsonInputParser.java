package ru.hackathon.heatnetwork.input;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.Model.*;
import ru.hackathon.heatnetwork.model.ObjectId;

/** Reads one geometry at a time; discarded properties never become a JSON tree. */
@Component
public final class GeoJsonInputParser implements InputParser {
    private static final long MAX_FILE_BYTES = 3L * 1024 * 1024 * 1024;
    private static final int MAX_DIAGNOSTICS = 100;
    private static final Set<String> PROPERTIES = Set.of("id", "object_type", "diameter", "flow_tph", "restriction_type");
    private static final Set<String> CRS_NAMES = Set.of("EPSG:4326", "urn:ogc:def:crs:EPSG::4326",
            "urn:ogc:def:crs:OGC:1.3:CRS84", "OGC:CRS84", "CRS84");
    private final Path storageDirectory;
    private final ObjectMapper mapper;

    @Autowired
    public GeoJsonInputParser(@Value("${heat-network.input.storage-directory:${java.io.tmpdir}/heat-network-routing}") String storageDirectory) {
        this(Path.of(storageDirectory));
    }

    public GeoJsonInputParser(Path storageDirectory) {
        this.storageDirectory = storageDirectory;
        mapper = new ObjectMapper();
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        mapper.enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
        mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    }

    @Override public Dataset parse(Path uploadedFile) throws IOException, InvalidInputException {
        if (Files.size(uploadedFile) > MAX_FILE_BYTES) throw error("Размер файла превышает 3 ГиБ");
        List<Diagnostic> issues = new ArrayList<>();
        FeatureDecoder decoder = new FeatureDecoder(mapper);
        try (DiskDataset.Builder store = new DiskDataset.Builder(storageDirectory, mapper);
             JsonParser parser = mapper.getFactory().createParser(uploadedFile.toFile())) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw error("Ожидается объект FeatureCollection");
            boolean collectionType = false, featuresSeen = false;
            long featureIndex = 0, sources = 0;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) throw error("Неверная структура FeatureCollection");
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (field) {
                    case "type":
                        if (value != JsonToken.VALUE_STRING || !"FeatureCollection".equals(parser.getText())) {
                            throw error("Корневой type должен быть FeatureCollection");
                        }
                        collectionType = true;
                        break;
                    case "features":
                        if (value != JsonToken.START_ARRAY) throw error("features должен быть массивом");
                        featuresSeen = true;
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Input parsing interrupted");
                            if (parser.currentToken() == null) throw error("Массив features неожиданно закончился");
                            JsonNode feature = readFeature(parser);
                            try {
                                InputObject object = decoder.decode(feature);
                                if (!store.add(object)) {
                                    issues.add(issue("INVALID_ID", "Повторяющийся id", object.id, featureIndex));
                                } else if (object.type == InputType.SOURCE) sources++;
                            } catch (FeatureDecoder.InvalidFeature e) {
                                issues.add(issue(e.code, e.getMessage(), e.objectId, featureIndex));
                            }
                            featureIndex++;
                            if (issues.size() >= MAX_DIAGNOSTICS) {
                                issues.get(issues.size() - 1).message += " Чтение остановлено после первых 100 ошибок.";
                                throw new InvalidInputException(issues);
                            }
                        }
                        break;
                    case "crs":
                        JsonNode crs = mapper.readTree(parser);
                        if (!crs.isNull() && (!crs.isObject() || !"name".equals(crs.path("type").asText())
                                || !CRS_NAMES.contains(crs.path("properties").path("name").asText()))) {
                            throw error("Входная CRS должна быть WGS 84 (EPSG:4326 / CRS84)");
                        }
                        break;
                    default: parser.skipChildren();
                }
            }
            if (!collectionType || !featuresSeen) throw error("Необходимы поля type и features");
            if (parser.nextToken() != null) throw error("После FeatureCollection обнаружены лишние JSON-данные");
            if (sources != 1) issues.add(issue("INVALID_INPUT", "Требуется ровно один корректный source; найдено: " + sources, null, -1));
            if (!issues.isEmpty()) throw new InvalidInputException(issues);
            return store.finish();
        } catch (JsonProcessingException e) {
            String location = e.getLocation() == null ? "" : " (строка " + e.getLocation().getLineNr()
                    + ", столбец " + e.getLocation().getColumnNr() + ")";
            throw error("Некорректный JSON" + location);
        }
    }

    private JsonNode readFeature(JsonParser parser) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return NullNode.instance;
        }
        ObjectNode feature = mapper.createObjectNode();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "type": feature.set(field, scalar(parser)); break;
                case "properties": feature.set(field, readMembers(parser, false)); break;
                case "geometry": feature.set(field, readMembers(parser, true)); break;
                default: parser.skipChildren();
            }
        }
        return feature;
    }

    private JsonNode readMembers(JsonParser parser, boolean geometry) throws IOException {
        if (parser.currentToken() != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return NullNode.instance;
        }
        ObjectNode members = mapper.createObjectNode();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            String field = parser.currentName();
            parser.nextToken();
            if (geometry && "coordinates".equals(field) && parser.currentToken() == JsonToken.START_ARRAY) {
                members.set(field, mapper.readTree(parser));
            } else if ((geometry && Set.of("type", "coordinates").contains(field)) || (!geometry && PROPERTIES.contains(field))) {
                members.set(field, scalar(parser));
            } else parser.skipChildren();
        }
        return members;
    }

    private JsonNode scalar(JsonParser parser) throws IOException {
        switch (parser.currentToken()) {
            case VALUE_STRING: return TextNode.valueOf(parser.getText());
            case VALUE_NUMBER_INT: return BigIntegerNode.valueOf(parser.getBigIntegerValue());
            case VALUE_NUMBER_FLOAT: return DecimalNode.valueOf(parser.getDecimalValue());
            case VALUE_TRUE: return BooleanNode.TRUE;
            case VALUE_FALSE: return BooleanNode.FALSE;
            case VALUE_NULL: return NullNode.instance;
            default:
                parser.skipChildren();
                return NullNode.instance;
        }
    }

    private static InvalidInputException error(String message) {
        return new InvalidInputException(List.of(issue("INVALID_INPUT", message, null, -1)));
    }

    private static Diagnostic issue(String code, String message, ObjectId id, long index) {
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.code = code;
        diagnostic.message = (index < 0 ? "" : "features[" + index + "]: ") + message;
        diagnostic.inputObjectId = id;
        return diagnostic;
    }
}
