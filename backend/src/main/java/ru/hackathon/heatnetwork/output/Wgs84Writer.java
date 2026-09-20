package ru.hackathon.heatnetwork.output;

import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/** Per-export inverse projection. Reuses two coordinates, even for very large LineStrings. */
final class Wgs84Writer {
    private final CoordinateTransform transform;
    private final ProjCoordinate source = new ProjCoordinate();
    private final ProjCoordinate target = new ProjCoordinate();

    Wgs84Writer() {
        CRSFactory factory = new CRSFactory();
        transform = new CoordinateTransformFactory().createTransform(
                factory.createFromParameters("EPSG:32637", "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs"),
                factory.createFromParameters("EPSG:4326", "+proj=longlat +datum=WGS84 +no_defs"));
    }

    void check(double x, double y) throws InvalidResultException {
        if (!Double.isFinite(x) || !Double.isFinite(y)) throw new InvalidResultException("Координаты должны быть конечными числами.");
        source.x = x;
        source.y = y;
        try { transform.transform(source, target); }
        catch (RuntimeException exception) { throw new InvalidResultException("Координаты нельзя преобразовать из EPSG:32637 в WGS 84."); }
        if (!Double.isFinite(target.x) || !Double.isFinite(target.y)
                || target.x < -180 || target.x > 180 || target.y <= -90 || target.y >= 90) {
            throw new InvalidResultException("Преобразование в WGS 84 дало координаты вне допустимого диапазона.");
        }
    }

    void position(JsonGenerator json, double x, double y) throws IOException {
        check(x, y);
        json.writeStartArray();
        json.writeNumber(target.x);
        json.writeNumber(target.y);
        json.writeEndArray();
    }
}
