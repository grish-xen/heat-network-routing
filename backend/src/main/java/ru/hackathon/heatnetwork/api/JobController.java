package ru.hackathon.heatnetwork.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import javax.servlet.http.HttpServletResponse;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
    private final JobService jobs;
    public JobController(JobService jobs) { this.jobs = jobs; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "createJob", summary = "Загрузить GeoJSON и запустить расчёт",
            description = "202 означает приём файла. Проверка, поиск маршрутов, расчёт и экспорт выполняются асинхронно.")
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

    @GetMapping(value = "/{jobId}/variants", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listVariants", summary = "Получить до трёх рассчитанных вариантов по возрастанию score")
    @ApiResponse(responseCode = "200", description = "Ранжированные сводки вариантов")
    @ApiResponse(responseCode = "404", description = "Задача не найдена", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Результат не готов", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<VariantSummaryView>> variants(@PathVariable String jobId) throws IOException {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jobs.variants(jobId));
    }

    @GetMapping(value = "/{jobId}/result", produces = "application/geo+json")
    @Operation(operationId = "downloadResult", summary = "Скачать рассчитанный GeoJSON")
    @ApiResponse(responseCode = "200", description = "GeoJSON передаётся потоком", content = @Content(mediaType = "application/geo+json", schema = @Schema(type = "string", format = "binary")))
    @ApiResponse(responseCode = "404", description = "Задача не найдена", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Результат не готов", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Resource> result(@PathVariable String jobId) throws IOException {
        FileJobStore.Download download = jobs.download(jobId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/geo+json"))
                .contentLength(download.length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("heat-network-" + jobId + ".geojson").build().toString())
                .body(new InputStreamResource(download.stream));
    }

    @GetMapping(value = "/{jobId}/map", produces = "application/geo+json")
    @Operation(operationId = "getMapPage", summary = "Получить страницу объектов карты в границах WGS 84",
            description = "layer=input или result; bbox=minLon,minLat,maxLon,maxLat. Для result нужен variantId. limit=1..5000, по умолчанию 1000. nextCursor используется с теми же параметрами; null означает конец.")
    @Parameters({
        @Parameter(name = "layer", in = ParameterIn.QUERY, required = true, schema = @Schema(type = "string", allowableValues = {"input", "result"})),
        @Parameter(name = "bbox", in = ParameterIn.QUERY, required = true, description = "minLon,minLat,maxLon,maxLat", schema = @Schema(type = "string")),
        @Parameter(name = "variantId", in = ParameterIn.QUERY, description = "Обязателен для слоя result", schema = @Schema(type = "string")),
        @Parameter(name = "limit", in = ParameterIn.QUERY, schema = @Schema(type = "integer", minimum = "1", maximum = "5000", defaultValue = "1000")),
        @Parameter(name = "cursor", in = ParameterIn.QUERY, schema = @Schema(type = "string"))
    })
    @ApiResponse(responseCode = "200", description = "Страница GeoJSON до 8 МиБ", content = @Content(mediaType = "application/geo+json", schema = @Schema(implementation = MapPageView.class)))
    @ApiResponse(responseCode = "400", description = "Некорректный запрос карты", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Задача или вариант не найдены", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Данные карты недоступны", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "413", description = "Один объект превышает лимит страницы", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public void map(@PathVariable String jobId, @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> parameters,
                    HttpServletResponse response) throws IOException {
        for (List<String> values : parameters.values()) {
            if (values.size() != 1) throw MapQuery.bad("INVALID_MAP_QUERY", "Параметры запроса карты не должны повторяться.");
        }
        MapQuery query = new MapQuery(jobId, parameters.getFirst("layer"), parameters.getFirst("bbox"),
                parameters.getFirst("variantId"), parameters.getFirst("limit"), parameters.getFirst("cursor"));
        try (MapArchive.Page page = jobs.map(jobId, query)) {
            response.setContentType("application/geo+json");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            page.writeTo(response.getOutputStream());
        }
    }

    /** Documentation schema only; production pages are written from disk without a feature tree. */
    @Schema(name = "MapPage")
    public static final class MapPageView {
        @Schema(required = true, allowableValues = "FeatureCollection") public String type;
        @Schema(required = true) public List<com.fasterxml.jackson.databind.JsonNode> features;
        @Schema(required = true, nullable = true) public String nextCursor;
    }
}
