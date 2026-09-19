package ru.hackathon.heatnetwork.model;

import java.util.Optional;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Envelope;
import ru.hackathon.heatnetwork.model.Model.InputObject;
import ru.hackathon.heatnetwork.model.Model.InputType;

/** Disk/database-backed access is possible; callers close every returned Stream. */
public interface Dataset extends AutoCloseable {
    Stream<InputObject> objects(InputType type);
    Optional<InputObject> find(ObjectId id);
    /** Bounding-box candidates in EPSG:32637; exact intersection is a caller check. */
    Stream<InputObject> query(Envelope bounds);
    @Override void close();
}
