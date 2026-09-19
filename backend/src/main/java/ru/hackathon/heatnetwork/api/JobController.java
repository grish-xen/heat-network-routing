package ru.hackathon.heatnetwork.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobs;
    public JobController(JobService jobs) { this.jobs = jobs; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createJob", summary = "Загрузить GeoJSON и запустить проверку",
            description = "202 означает приём файла. Текущая сборка после успешной проверки возвращает FAILED / PROCESSING_UNAVAILABLE: расчётные модули ещё не подключены.")
    @ApiResponse(responseCode = "202", description = "Файл сохранён, задача принята", content = @Content(schema = @Schema(implementation = JobView.class)))
    @ApiResponse(responseCode = "400", description = "Некорректный запрос", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "413", description = "Превышен размер файла или запроса", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "422", description = "Режим пока не поддерживается", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "503", description = "Очередь заполнена", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "500", description = "Ошибка сохранения", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<JobView> create(@RequestParam("file") MultipartFile file,
            @Parameter(schema = @Schema(allowableValues = {"2d", "depth"}, defaultValue = "2d"))
            @RequestParam(value = "mode", required = false) String mode,
            @Parameter(hidden = true) MultipartHttpServletRequest request) throws IOException {
        long fileCount = request.getMultiFileMap().values().stream().mapToLong(List::size).sum();
        String[] modes = request.getParameterValues("mode");
        if (fileCount != 1 || (modes != null && modes.length != 1)) {
            throw new ApiException(400, "INVALID_INPUT", "Передайте ровно один файл file и не более одного значения mode.");
        }
        JobView job = jobs.submit(file, mode == null ? "2d" : mode);
        return ResponseEntity.accepted().location(URI.create("/api/jobs/" + job.jobId)).body(job);
    }

    @GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getJob", summary = "Получить статус задачи и ошибки проверки")
    @ApiResponse(responseCode = "200", description = "Текущий статус", content = @Content(schema = @Schema(implementation = JobView.class)))
    @ApiResponse(responseCode = "404", description = "Задача не найдена", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "500", description = "Ошибка чтения статуса", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<JobView> get(@PathVariable String jobId) throws IOException {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(jobs.get(jobId));
    }
}
