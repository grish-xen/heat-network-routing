package ru.hackathon.heatnetwork.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.operation.valid.IsValidOp;
import ru.hackathon.heatnetwork.model.Model;
import ru.hackathon.heatnetwork.model.Model.*;
import ru.hackathon.heatnetwork.model.ObjectId;

final class FeatureDecoder {
    private final GeometryFactory geometries = new GeometryFactory(new PrecisionModel(), Model.METRIC_SRID);
    private final Wgs84Projection projection = new Wgs84Projection();
    private final Set<Integer> diameters = new HashSet<>();

    FeatureDecoder(ObjectMapper mapper) throws IOException {
        try (InputStream source = getClass().getResourceAsStream("/rules/catalog-v1.json")) {
            if (source == null) throw new IOException("Missing diameter catalog");
            mapper.readTree(source).path("diameters").forEach(row -> diameters.add(row.path("diameterMm").intValue()));
        }
    }

    InputObject decode(JsonNode feature) throws InvalidFeature {
        ObjectId id = null;
        try {
            require(feature.isObject() && "Feature".equals(feature.path("type").asText()), "INVALID_INPUT", "Ожидается объект Feature");
            JsonNode p = feature.path("properties");
            require(p.isObject(), "INVALID_INPUT", "properties должен быть объектом");
            JsonNode rawId = p.path("id");
            require(rawId.isTextual() || rawId.isNumber(), "INVALID_ID", "properties.id должен быть строкой или числом");
            id = new ObjectId(rawId);
            String type = text(p, "object_type");
            InputObject result = new InputObject();
            result.id = id;
            switch (type) {
                case "source": result.type = InputType.SOURCE; break;
                case "heat_network": result.type = InputType.HEAT_NETWORK; break;
                case "heat_chamber": result.type = InputType.HEAT_CHAMBER; break;
                case "oks_connection_point": result.type = InputType.OKS_CONNECTION_POINT; break;
                case "restriction": result.type = InputType.RESTRICTION; break;
                default: throw invalid("INVALID_INPUT", "Неизвестный object_type: " + type);
            }
            if (result.type == InputType.HEAT_NETWORK) {
                JsonNode diameter = p.path("diameter");
                require(diameter.isNumber() && diameter.decimalValue().stripTrailingZeros().scale() <= 0
                        && diameter.canConvertToInt() && diameters.contains(diameter.intValue()),
                        "INVALID_INPUT", "diameter должен быть целым ДУ из справочника");
                result.diameterMm = diameter.intValue();
            }
            if (result.type == InputType.OKS_CONNECTION_POINT) {
                JsonNode flow = p.path("flow_tph");
                require(flow.isNumber() && flow.decimalValue().signum() >= 0,
                        "INVALID_INPUT", "flow_tph должен быть неотрицательным числом в т/ч");
                result.flowTph = flow.decimalValue();
            }
            if (result.type == InputType.RESTRICTION) result.restrictionType = text(p, "restriction_type");
            JsonNode g = feature.path("geometry");
            require(g.isObject(), "INVALID_GEOMETRY", "geometry должен быть непустым объектом");
            String kind = g.path("type").asText();
            if (result.type == InputType.HEAT_NETWORK) {
                require("LineString".equals(kind), "INVALID_GEOMETRY", "heat_network требует LineString");
            } else if (result.type != InputType.RESTRICTION) {
                require("Point".equals(kind), "INVALID_GEOMETRY", type + " требует Point");
            } else {
                require(Set.of("LineString", "MultiLineString", "Polygon", "MultiPolygon").contains(kind),
                        "INVALID_GEOMETRY", "Недопустимая геометрия restriction");
            }
            result.geometry = geometry(kind, g.path("coordinates"));
            require(!result.geometry.isEmpty(), "INVALID_GEOMETRY", "Пустая геометрия");
            if (result.geometry.getDimension() == 1) {
                for (int i = 0; i < result.geometry.getNumGeometries(); i++) {
                    require(result.geometry.getGeometryN(i).getLength() > 0, "INVALID_GEOMETRY", "Линия нулевой длины");
                }
            }
            IsValidOp valid = new IsValidOp(result.geometry);
            require(valid.isValid(), "INVALID_GEOMETRY", "Некорректная геометрия: " + valid.getValidationError());
            return result;
        } catch (InvalidFeature e) {
            e.objectId = id;
            throw e;
        } catch (IllegalArgumentException e) {
            InvalidFeature issue = invalid("INVALID_GEOMETRY", "Невозможно построить геометрию: " + e.getMessage());
            issue.objectId = id;
            throw issue;
        }
    }

    private String text(JsonNode p, String key) throws InvalidFeature {
        require(p.path(key).isTextual() && !p.path(key).textValue().isBlank(), "INVALID_INPUT", key + " должен быть непустой строкой");
        return p.path(key).textValue();
    }

    private Geometry geometry(String kind, JsonNode c) throws InvalidFeature {
        switch (kind) {
            case "Point": return geometries.createPoint(point(c));
            case "LineString": return geometries.createLineString(line(c, false));
            case "Polygon": return polygon(c);
            case "MultiLineString":
                array(c, 1);
                LineString[] lines = new LineString[c.size()];
                for (int i = 0; i < lines.length; i++) lines[i] = geometries.createLineString(line(c.get(i), false));
                return geometries.createMultiLineString(lines);
            case "MultiPolygon":
                array(c, 1);
                Polygon[] polygons = new Polygon[c.size()];
                for (int i = 0; i < polygons.length; i++) polygons[i] = polygon(c.get(i));
                return geometries.createMultiPolygon(polygons);
            default: throw invalid("INVALID_GEOMETRY", "Неподдерживаемая геометрия");
        }
    }

    private Polygon polygon(JsonNode c) throws InvalidFeature {
        array(c, 1);
        LinearRing shell = geometries.createLinearRing(line(c.get(0), true));
        LinearRing[] holes = new LinearRing[c.size() - 1];
        for (int i = 0; i < holes.length; i++) holes[i] = geometries.createLinearRing(line(c.get(i + 1), true));
        return geometries.createPolygon(shell, holes);
    }

    private Coordinate[] line(JsonNode c, boolean ring) throws InvalidFeature {
        array(c, ring ? 4 : 2);
        Coordinate[] coordinates = new Coordinate[c.size()];
        for (int i = 0; i < coordinates.length; i++) coordinates[i] = point(c.get(i));
        require(!ring || coordinates[0].equals2D(coordinates[coordinates.length - 1]), "INVALID_GEOMETRY", "Кольцо полигона не замкнуто");
        return coordinates;
    }

    private Coordinate point(JsonNode c) throws InvalidFeature {
        array(c, 2);
        require(c.size() <= 3, "INVALID_GEOMETRY", "Позиция должна содержать 2 или 3 координаты");
        for (JsonNode value : c) require(value.isNumber() && Double.isFinite(value.doubleValue()), "INVALID_GEOMETRY", "Координаты должны быть конечными числами");
        double lon = c.get(0).doubleValue(), lat = c.get(1).doubleValue();
        require(lon >= -180 && lon <= 180 && lat > -90 && lat < 90, "INVALID_GEOMETRY", "Координаты вне диапазона WGS 84 longitude/latitude");
        try {
            return projection.project(lon, lat);
        } catch (RuntimeException e) {
            throw invalid("INVALID_GEOMETRY", "Не удалось преобразовать координату в EPSG:32637");
        }
    }

    private void array(JsonNode n, int min) throws InvalidFeature {
        require(n.isArray() && n.size() >= min, "INVALID_GEOMETRY", "Неверная структура массива coordinates");
    }

    private static void require(boolean condition, String code, String message) throws InvalidFeature {
        if (!condition) throw invalid(code, message);
    }

    private static InvalidFeature invalid(String code, String message) { return new InvalidFeature(code, message); }

    static final class InvalidFeature extends Exception {
        final String code;
        ObjectId objectId;
        InvalidFeature(String code, String message) { super(message); this.code = code; }
    }
}
