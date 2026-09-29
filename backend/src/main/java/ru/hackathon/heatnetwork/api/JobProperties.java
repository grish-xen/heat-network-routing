package ru.hackathon.heatnetwork.api;

import java.nio.file.Path;
import java.time.Duration;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("heat-network.jobs")
public class JobProperties {
    @NotNull private Path storageDirectory = Path.of(System.getProperty("java.io.tmpdir"), "heat-network-routing", "jobs");
    @Min(1) @Max(50) private int workers = 2;
    @Min(0) @Max(1000) private int queueCapacity = 48;
    @Min(1) @Max(3221225472L) private long maxFileBytes = 3L * 1024 * 1024 * 1024;
    @NotNull private Duration retention = Duration.ofHours(24);
    @Min(1) @Max(10000) private int maxCandidates = 100;
    private long searchSeed = 0;
    /** Planner strategies run per job (seed, seed + 1, ...); up to three best distinct variants are kept. */
    @Min(1) @Max(3) private int variantStrategies = 3;
    /** Shared wall-clock budget of the alternative strategies, which run in parallel after the main one. */
    @NotNull private Duration alternativeTimeout = Duration.ofSeconds(60);

    public Path getStorageDirectory() { return storageDirectory; }
    public void setStorageDirectory(Path value) { storageDirectory = value; }
    public int getWorkers() { return workers; }
    public void setWorkers(int value) { workers = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long value) { maxFileBytes = value; }
    public Duration getRetention() { return retention; }
    public void setRetention(Duration value) { retention = value; }
    public int getMaxCandidates() { return maxCandidates; }
    public void setMaxCandidates(int value) { maxCandidates = value; }
    public long getSearchSeed() { return searchSeed; }
    public void setSearchSeed(long value) { searchSeed = value; }
    public int getVariantStrategies() { return variantStrategies; }
    public void setVariantStrategies(int value) { variantStrategies = value; }
    public Duration getAlternativeTimeout() { return alternativeTimeout; }
    public void setAlternativeTimeout(Duration value) { alternativeTimeout = value; }
}
