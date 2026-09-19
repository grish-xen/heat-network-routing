package ru.hackathon.heatnetwork;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.hackathon.heatnetwork.model.ObjectId;
import static org.junit.jupiter.api.Assertions.*;

class ObjectIdTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void preservesNumericAndTextualIdsAcrossJsonRoundTrip() throws Exception {
        ObjectId number = mapper.readValue("1", ObjectId.class);
        ObjectId text = mapper.readValue("\"1\"", ObjectId.class);
        assertNotEquals(number, text);
        assertEquals("1", mapper.writeValueAsString(number));
        assertEquals("\"1\"", mapper.writeValueAsString(text));
        assertEquals(number, mapper.readValue("1.0", ObjectId.class));
        assertEquals(number.hashCode(), mapper.readValue("1.0", ObjectId.class).hashCode());
    }

    @Test void rejectsNonScalarOrBooleanIds() throws Exception {
        for (String json : new String[]{"null", "true", "{}", "[]"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ObjectId(mapper.readTree(json)));
        }
    }

    @Test void preservesLargeNumericIdWithoutFloatingPointConversion() throws Exception {
        String id = "9007199254740993";
        assertEquals(id, mapper.writeValueAsString(mapper.readValue(id, ObjectId.class)));
    }
}
