package ru.hackathon.heatnetwork.input;

import java.util.List;
import ru.hackathon.heatnetwork.model.Model.Diagnostic;

public class InvalidInputException extends Exception {
    private final List<Diagnostic> diagnostics;
    public InvalidInputException(List<Diagnostic> diagnostics) {
        super("Input validation failed");
        this.diagnostics = List.copyOf(diagnostics);
    }
    public List<Diagnostic> getDiagnostics() { return diagnostics; }
}
