package ru.hackathon.heatnetwork.output;

import java.io.IOException;

/** A calculated result violates the serialization contract; this is not an input-file error. */
public final class InvalidResultException extends IOException {
    public InvalidResultException(String message) { super(message); }
}
