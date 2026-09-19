package ru.hackathon.heatnetwork.input;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.locationtech.jts.geom.*;
import ru.hackathon.heatnetwork.model.Dataset;
import ru.hackathon.heatnetwork.model.ObjectId;
import ru.hackathon.heatnetwork.model.Model.*;
import static org.junit.jupiter.api.Assertions.*;

class GeoJsonInputParserTest {
    @TempDir Path temporary;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);

    private Path fixture(String name) throws Exception {
        return Path.of(Objects.requireNonNull(getClass().getResource("/fixtures/" + name)).toURI());
    }
    private ObjectNode toy() throws Exception {
        return (ObjectNode) json.readTree(fixture("synthetic/two-consumers/input.geojson").toFile());
    }
    private Path store() { return temporary.resolve("datasets"); }
    private GeoJsonInputParser parser() { return new GeoJsonInputParser(store()); }
    private ObjectId id(String literal) throws Exception { return new ObjectId(json.readTree(literal)); }
    private Dataset parse(JsonNode root) throws Exception {
        Path file = temporary.resolve("input.geojson");
        json.writeValue(file.toFile(), root);
        return parser().parse(file);
    }
    private ObjectNode props(ObjectNode root, int index) {
        return (ObjectNode) root.path("features").get(index).path("properties");
    }
    private long count(Dataset dataset, InputType type) {
        try (Stream<InputObject> objects = dataset.objects(type)) { return objects.count(); }
    }
    private void emptyStore() throws IOException {
        if (Files.exists(store())) try (Stream<Path> children = Files.list(store())) { assertEquals(0, children.count()); }
    }

    @Test void projectsKnownFixtureAndPreservesAttributes() throws Exception {
        try (Dataset dataset = parser().parse(fixture("synthetic/two-consumers/input.geojson"))) {
            assertEquals(1, count(dataset, InputType.SOURCE));
            assertEquals(2, count(dataset, InputType.OKS_CONNECTION_POINT));
            InputObject line = dataset.find(id("\"existing-line\"")).orElseThrow();
            assertEquals(300, line.diameterMm);
            assertEquals(32637, line.geometry.getSRID());
            assertEquals(100, line.geometry.getLength(), 0.001);
            InputObject a = dataset.find(id("1")).orElseThrow();
            assertEquals(400100, a.geometry.getCoordinate().x, 0.001);
            assertEquals(6170050, a.geometry.getCoordinate().y, 0.001);
            assertEquals(0, new BigDecimal("10").compareTo(a.flowTph));
            assertFalse(dataset.find(id("\"missing\"")).isPresent());
        }
        emptyStore();
    }

    @Test void readsCompetitionDatasetThroughUnchangedContract() throws Exception {
        try (Dataset dataset = parser().parse(fixture("competition-corrected.geojson"))) {
            assertEquals(1, count(dataset, InputType.SOURCE));
            assertEquals(29, count(dataset, InputType.HEAT_NETWORK));
            assertEquals(9, count(dataset, InputType.HEAT_CHAMBER));
            assertEquals(17, count(dataset, InputType.OKS_CONNECTION_POINT));
            assertEquals(88, count(dataset, InputType.RESTRICTION));
            try (Stream<InputObject> points = dataset.objects(InputType.OKS_CONNECTION_POINT)) {
                assertEquals(0, new BigDecimal("488.72").compareTo(points.map(p -> p.flowTph).reduce(BigDecimal.ZERO, BigDecimal::add)));
            }
        }
        emptyStore();
    }

    @Test void distinguishesTextIdsAndPreservesLargeDecimalNumbers() throws Exception {
        ObjectNode root = toy();
        props(root, 4).put("id", "1");
        try (Dataset data = parse(root)) {
            assertEquals(0, BigDecimal.TEN.compareTo(data.find(id("1.0")).orElseThrow().flowTph));
            assertEquals(0, new BigDecimal("15").compareTo(data.find(id("\"1\"")).orElseThrow().flowTph));
        }
        props(root, 4).set("id", json.readTree("9007199254740993.125"));
        props(root, 4).set("flow_tph", json.readTree("15.1234567890123456789"));
        try (Dataset data = parse(root)) {
            InputObject value = data.find(id("9007199254740993.125")).orElseThrow();
            assertEquals("9007199254740993.125", value.id.value().asText());
            assertEquals(new BigDecimal("15.1234567890123456789"), value.flowTph);
        }
    }

    @Test void rejectsEquivalentDuplicateIdsAndCleansTemporaryFiles() throws Exception {
        ObjectNode root = toy();
        props(root, 4).set("id", json.readTree("1.0"));
        InvalidInputException error = assertThrows(InvalidInputException.class, () -> parse(root));
        assertTrue(error.getDiagnostics().stream().anyMatch(d -> "INVALID_ID".equals(d.code) && d.message.contains("features[4]")));
        emptyStore();
        assertTrue(Files.exists(temporary.resolve("input.geojson")));
    }

    static Stream<Arguments> invalidProperties() {
        return Stream.of(Arguments.of(4,"id","null","INVALID_ID"), Arguments.of(4,"id","true","INVALID_ID"),
                Arguments.of(4,"id","{}","INVALID_ID"), Arguments.of(4,"flow_tph","-1","INVALID_INPUT"),
                Arguments.of(4,"flow_tph","\"15\"","INVALID_INPUT"), Arguments.of(4,"flow_tph","true","INVALID_INPUT"),
                Arguments.of(4,"object_type","\"unknown\"","INVALID_INPUT"), Arguments.of(1,"diameter","350","INVALID_INPUT"),
                Arguments.of(1,"diameter","300.5","INVALID_INPUT"), Arguments.of(1,"diameter","\"300\"","INVALID_INPUT"));
    }

    @ParameterizedTest @MethodSource("invalidProperties")
    void reportsInvalidAttributes(int index, String key, String value, String code) throws Exception {
        ObjectNode root = toy();
        props(root,index).set(key,json.readTree(value));
        InvalidInputException error = assertThrows(InvalidInputException.class, () -> parse(root));
        assertTrue(error.getDiagnostics().stream().anyMatch(d -> code.equals(d.code)));
        emptyStore();
    }

    @Test void acceptsZeroDemandAndAdditionalFields() throws Exception {
        ObjectNode root = toy();
        props(root,4).put("flow_tph",0);
        props(root,4).putObject("custom").putArray("ignored").add(42);
        root.putObject("metadata").put("title","test");
        root.putObject("crs").put("type","name").putObject("properties").put("name","urn:ogc:def:crs:OGC:1.3:CRS84");
        try (Dataset data = parse(root)) {
            assertEquals(0, data.find(id("\"2\"")).orElseThrow().flowTph.signum());
        }
    }

    @Test void supportsHolesMultiPolygonsAndMultiLines() throws Exception {
        ObjectNode root = toy();
        String polygon = "[[[37.60,55.75],[37.61,55.75],[37.61,55.76],[37.60,55.76],[37.60,55.75]],[[37.602,55.752],[37.602,55.754],[37.604,55.754],[37.604,55.752],[37.602,55.752]]]";
        appendRestriction(root,"polygon","Polygon",polygon);
        appendRestriction(root,"multi-polygon","MultiPolygon","["+polygon+"]");
        appendRestriction(root,"multi-line","MultiLineString","[[[37.62,55.75],[37.63,55.76]],[[37.64,55.75],[37.65,55.76]]]");
        try (Dataset data = parse(root)) {
            Polygon g = (Polygon) data.find(id("\"polygon\"")).orElseThrow().geometry;
            assertEquals(1,g.getNumInteriorRing());
            assertEquals(2,data.find(id("\"multi-line\"")).orElseThrow().geometry.getNumGeometries());
            assertEquals(3,count(data,InputType.RESTRICTION));
            assertEquals("custom_type",data.find(id("\"polygon\"")).orElseThrow().restrictionType);
        }
    }

    private void appendRestriction(ObjectNode root,String id,String type,String coords) throws Exception {
        ObjectNode f=((ArrayNode)root.path("features")).addObject();
        f.put("type","Feature");
        f.putObject("properties").put("id",id).put("object_type","restriction").put("restriction_type","custom_type");
        f.putObject("geometry").put("type",type).set("coordinates",json.readTree(coords));
    }

    static Stream<Arguments> invalidGeometry() {
        return Stream.of(Arguments.of("Polygon","[[[37.6,55.75],[37.61,55.76],[37.6,55.76],[37.61,55.75],[37.6,55.75]]]"),
                Arguments.of("Polygon","[[[37.6,55.75],[37.61,55.75],[37.61,55.76],[37.6,55.76]]]"),
                Arguments.of("MultiPolygon","[]"),Arguments.of("LineString","[[37.6,55.75],[37.6,55.75]]"),
                Arguments.of("LineString","[[181,55.75],[37.61,55.75]]"),Arguments.of("LineString","[[37.6,91],[37.61,55.75]]"),
                Arguments.of("LineString","[[\"37.6\",55.75],[37.61,55.75]]"));
    }

    @ParameterizedTest @MethodSource("invalidGeometry")
    void rejectsInvalidGeometryWithoutRepair(String type,String coords) throws Exception {
        ObjectNode root=toy();
        appendRestriction(root,"bad",type,coords);
        InvalidInputException error=assertThrows(InvalidInputException.class,()->parse(root));
        assertTrue(error.getDiagnostics().stream().anyMatch(d->"INVALID_GEOMETRY".equals(d.code)));
        emptyStore();
    }

    @Test void acceptsOptionalAltitudeButComputesInTwoDimensions() throws Exception {
        ObjectNode root=toy();
        ((ArrayNode)root.path("features").get(4).path("geometry").path("coordinates")).add(123);
        try(Dataset data=parse(root)) {
            assertTrue(Double.isNaN(data.find(id("\"2\"")).orElseThrow().geometry.getCoordinate().getZ()));
        }
    }

    @Test void validatesRootStructureCrsAndExactlyOneSource() throws Exception {
        for(String raw:List.of("{}","[]","{\"type\":\"FeatureCollection\",\"features\":[]}","{\"type\":\"FeatureCollection\",\"features\":[")) {
            Path input=temporary.resolve("invalid.json");
            Files.writeString(input,raw);
            assertThrows(InvalidInputException.class,()->parser().parse(input));
            emptyStore();
        }
        ObjectNode root=toy();
        root.putObject("crs").put("type","name").putObject("properties").put("name","EPSG:3857");
        assertThrows(InvalidInputException.class,()->parse(root));
        root.remove("crs");
        ObjectNode source=root.path("features").get(0).deepCopy();
        ((ObjectNode)source.path("properties")).put("id","second-source");
        ((ArrayNode)root.path("features")).add(source);
        assertThrows(InvalidInputException.class,()->parse(root));
        emptyStore();
    }

    @Test void rejectsDuplicateJsonFieldsAndTrailingJson() throws Exception {
        String input=toy().toString();
        for(String invalid:List.of(input+" {}",input.replace("\"flow_tph\":15","\"flow_tph\":15,\"flow_tph\":16"))) {
            assertNotEquals(input,invalid);
            Path file=temporary.resolve("malformed.json");
            Files.writeString(file,invalid);
            assertThrows(InvalidInputException.class,()->parser().parse(file));
            emptyStore();
        }
    }

    @Test void spatialStreamsAreLazyIndependentAndClosedWithDataset() throws Exception {
        Dataset data=parse(toy());
        Envelope bounds=new Envelope(399999,400001,6169999,6170001);
        try(Stream<InputObject> hits=data.query(bounds)) {
            bounds.expandToInclude(400200,6170100);
            Set<InputType> types=hits.map(o->o.type).collect(Collectors.toSet());
            assertEquals(Set.of(InputType.HEAT_NETWORK,InputType.HEAT_CHAMBER),types);
        }
        InputObject first=data.find(id("1")).orElseThrow();
        first.geometry.getCoordinate().x=0;
        assertEquals(400100,data.find(id("1")).orElseThrow().geometry.getCoordinate().x,0.001);
        try(Stream<InputObject> empty=data.query(new Envelope())) { assertEquals(0,empty.count()); }
        Stream<InputObject> points=data.objects(InputType.OKS_CONNECTION_POINT);
        Stream<InputObject> lines=data.objects(InputType.HEAT_NETWORK);
        assertTrue(points.findFirst().isPresent());
        data.close();
        data.close();
        emptyStore();
        assertThrows(IllegalStateException.class,lines::count);
        assertThrows(IllegalStateException.class,()->data.find(id("1")));
        points.close(); lines.close();
    }

    @Test void preservesIoFailuresAndCleansOnInterruption() throws Exception {
        assertThrows(IOException.class,()->parser().parse(temporary.resolve("missing.json")));
        Path fixture=fixture("synthetic/two-consumers/input.geojson");
        Thread.currentThread().interrupt();
        try { assertThrows(java.io.InterruptedIOException.class,()->parser().parse(fixture)); }
        finally { Thread.interrupted(); }
        emptyStore();
    }

    @Test void limitsDiagnosticAccumulation() throws Exception {
        ObjectNode root=toy();
        ArrayNode features=(ArrayNode)root.path("features");
        for(int i=0;i<150;i++) {
            ObjectNode bad=features.get(4).deepCopy();
            ((ObjectNode)bad.path("properties")).remove("flow_tph");
            features.add(bad);
        }
        InvalidInputException error=assertThrows(InvalidInputException.class,()->parse(root));
        assertEquals(100,error.getDiagnostics().size());
        assertTrue(error.getDiagnostics().get(99).message.contains("100"));
        emptyStore();
    }
}
