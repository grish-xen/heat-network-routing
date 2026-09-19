package ru.hackathon.heatnetwork.api;

final class ApiException extends RuntimeException {
    final int status;
    final ApiError error;

    ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.error = new ApiError(code, message);
    }
}
