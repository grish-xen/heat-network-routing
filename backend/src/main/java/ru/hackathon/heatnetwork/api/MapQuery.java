package ru.hackathon.heatnetwork.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** Validated WGS84 viewport and a cursor bound to the job and all query parameters. */
final class MapQuery {
    final String layer;
    final String variantId;
    final double minX, minY, maxX, maxY;
    final int limit;
    final long offset;
    private final String fingerprint;

    MapQuery(String jobId, String layer, String bbox, String variantId, String limit, String cursor) {
        if (!"input".equals(layer) && !"result".equals(layer)) throw bad("INVALID_LAYER", "layer должен быть input или result.");
        if ("result".equals(layer) && (variantId == null || variantId.isBlank())) throw bad("INVALID_VARIANT", "Для слоя result укажите variantId.");
        if ("input".equals(layer) && variantId != null) throw bad("INVALID_VARIANT", "variantId применяется только к слою result.");
        this.layer = layer;
        this.variantId = variantId;
        double[] values = new double[4];
        try {
            String[] parts = bbox == null ? new String[0] : bbox.split(",", -1);
            if (parts.length != 4) throw new NumberFormatException();
            for (int i = 0; i < 4; i++) {
                values[i] = Double.parseDouble(parts[i].trim());
                if (!Double.isFinite(values[i])) throw new NumberFormatException();
                if (values[i] == 0) values[i] = 0; // canonicalize negative zero
            }
            if (values[0] < -180 || values[2] > 180 || values[1] < -90 || values[3] > 90
                    || values[0] >= values[2] || values[1] >= values[3]) throw new NumberFormatException();
        } catch (NumberFormatException invalid) {
            throw bad("INVALID_BBOX", "bbox: minLon,minLat,maxLon,maxLat; границы должны возрастать и находиться в WGS 84.");
        }
        minX = values[0]; minY = values[1]; maxX = values[2]; maxY = values[3];
        try {
            this.limit = limit == null ? 1000 : Integer.parseInt(limit);
            if (this.limit < 1 || this.limit > 5000) throw new NumberFormatException();
        } catch (NumberFormatException invalid) { throw bad("INVALID_LIMIT", "limit должен быть целым числом от 1 до 5000."); }
        String identity = jobId + "\n" + layer + "\n" + variantId + "\n"
                + minX + "," + minY + "," + maxX + "," + maxY + "\n" + this.limit;
        try {
            fingerprint = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        if (cursor == null) offset = 0;
        else {
            try {
                if (cursor.length() > 160) throw new IllegalArgumentException();
                String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
                String[] parts = decoded.split(":", -1);
                if (parts.length != 3 || !"1".equals(parts[0]) || !fingerprint.equals(parts[1])) throw new IllegalArgumentException();
                offset = Long.parseLong(parts[2]);
                if (offset < 0 || !cursor.equals(cursor(offset))) throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw bad("INVALID_CURSOR", "Курсор некорректен или относится к другому запросу карты."); }
        }
    }

    String cursor(long offset) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(("1:" + fingerprint + ":" + offset).getBytes(StandardCharsets.UTF_8));
    }
    boolean intersects(double minX, double minY, double maxX, double maxY) {
        return this.minX <= maxX && this.maxX >= minX && this.minY <= maxY && this.maxY >= minY;
    }
    static ApiException bad(String code, String message) { return new ApiException(400, code, message); }
}
