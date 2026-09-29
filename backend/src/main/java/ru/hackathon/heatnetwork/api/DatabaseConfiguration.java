package ru.hackathon.heatnetwork.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PostgreSQL job metadata, enabled when {@code spring.datasource.url} is set (Compose sets
 * SPRING_DATASOURCE_URL/USERNAME/PASSWORD). The default DataSource auto-configuration is excluded in
 * application.properties, so the backend still starts without a database for local work and tests.
 * The schema is created on start-up with {@code CREATE TABLE IF NOT EXISTS} statements.
 */
@Configuration
@ConditionalOnProperty("spring.datasource.url")
@EnableConfigurationProperties(DataSourceProperties.class)
class DatabaseConfiguration {

    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    JobRecords jobRecords(DataSource dataSource, ObjectMapper mapper) {
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return new JdbcJobRecords(new JdbcTemplate(dataSource), transactions, mapper);
    }
}
