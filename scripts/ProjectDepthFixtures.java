import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateTransformFactory;
import org.locationtech.proj4j.ProjCoordinate;

/** Fixture generation only. Uses the application's pinned Proj4J; one XY pair per stdin line. */
class ProjectDepthFixtures {
    public static void main(String[] args) throws Exception {
        CRSFactory crs = new CRSFactory();
        var transform = new CoordinateTransformFactory().createTransform(
                crs.createFromParameters("metric", "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs"),
                crs.createFromParameters("wgs84", "+proj=longlat +datum=WGS84 +no_defs"));
        try (var reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] xy = line.split(" ");
                var result = new ProjCoordinate();
                transform.transform(new ProjCoordinate(Double.parseDouble(xy[0]), Double.parseDouble(xy[1])), result);
                System.out.println(result.x + " " + result.y);
            }
        }
    }
}
