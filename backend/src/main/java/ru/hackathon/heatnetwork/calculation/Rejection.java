package ru.hackathon.heatnetwork.calculation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import ru.hackathon.heatnetwork.model.Model.Diagnostic;
import ru.hackathon.heatnetwork.model.ObjectId;

/** Candidate rejection with the reasons; never used for internal failures. */
final class Rejection extends Exception {
    private static final long serialVersionUID = 1L;
    final List<Diagnostic> diagnostics;

    Rejection(List<Diagnostic> diagnostics) {
        super(diagnostics.isEmpty() ? "rejected" : diagnostics.get(0).message, null, false, false);
        this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
    }

    static Rejection of(String code, String message, ObjectId inputObjectId, String segmentId) {
        return new Rejection(Collections.singletonList(diag(code, message, inputObjectId, segmentId)));
    }

    static Diagnostic diag(String code, String message, ObjectId inputObjectId, String segmentId) {
        Diagnostic diagnostic = new Diagnostic();
        diagnostic.code = code;
        diagnostic.message = message;
        diagnostic.inputObjectId = inputObjectId;
        diagnostic.segmentId = segmentId;
        return diagnostic;
    }

    static void throwIfAny(List<Diagnostic> diagnostics) throws Rejection {
        if (!diagnostics.isEmpty()) {
            throw new Rejection(diagnostics);
        }
    }
}
