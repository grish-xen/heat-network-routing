package ru.hackathon.heatnetwork.output;

import java.io.IOException;

public final class OutputLimitExceededException extends IOException {
    public OutputLimitExceededException(long limit) {
        super("Выходной GeoJSON превышает допустимый размер: " + limit + " байт.");
    }
}
