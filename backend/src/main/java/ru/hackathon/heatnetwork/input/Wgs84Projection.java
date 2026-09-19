package ru.hackathon.heatnetwork.input;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/** Longitude/latitude order, WGS84 ellipsoid, UTM zone 37 north, metres. */
final class Wgs84Projection {
    private final CoordinateTransform transform;

    Wgs84Projection() {
        CRSFactory crs = new CRSFactory();
        transform = new CoordinateTransformFactory().createTransform(
                crs.createFromParameters("EPSG:4326", "+proj=longlat +datum=WGS84 +no_defs"),
                crs.createFromParameters("EPSG:32637", "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs"));
    }

    Coordinate project(double longitude, double latitude) {
        ProjCoordinate result = new ProjCoordinate();
        transform.transform(new ProjCoordinate(longitude, latitude), result);
        if (!Double.isFinite(result.x) || !Double.isFinite(result.y)) {
            throw new IllegalArgumentException("Coordinate cannot be projected to EPSG:32637");
        }
        return new Coordinate(result.x, result.y);
    }
}
