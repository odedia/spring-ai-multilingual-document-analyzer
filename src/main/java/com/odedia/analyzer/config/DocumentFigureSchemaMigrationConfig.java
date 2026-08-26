package com.odedia.analyzer.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

/**
 * Creates {@code document_figure} before Hibernate starts. {@code ddl-auto: update}
 * is not safe as the only writer: this app runs two CF instances, and a new BYTEA
 * table must exist before the first upload.
 * <p>
 * Idempotent. Concurrent boots take a session-level advisory lock so only one
 * instance runs DDL.
 */
@Configuration
@AutoConfigureBefore(HibernateJpaAutoConfiguration.class)
public class DocumentFigureSchemaMigrationConfig {

	private static final Logger log = LoggerFactory.getLogger(DocumentFigureSchemaMigrationConfig.class);

	private static final long MIGRATION_LOCK_KEY = 872_314_002L;

	@Bean
	static BeanFactoryPostProcessor documentFigureSchemaBeforeHibernate() {
		return beanFactory -> {
			if (!beanFactory.containsBeanDefinition("entityManagerFactory")) {
				return;
			}
			var def = beanFactory.getBeanDefinition("entityManagerFactory");
			if (def instanceof AbstractBeanDefinition abd) {
				abd.setDependsOn(StringUtils.concatenateStringArrays(
						abd.getDependsOn(),
						new String[] { "documentFigureSchemaMigration" }));
			}
		};
	}

	@Bean(name = "documentFigureSchemaMigration")
	Object documentFigureSchemaMigration(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.execute((Connection connection) -> {
			migrateIfNeeded(connection);
			return null;
		});
		return new Object();
	}

	private static void migrateIfNeeded(Connection connection) throws SQLException {
		try (Statement st = connection.createStatement()) {
			st.execute("SELECT pg_advisory_lock(" + MIGRATION_LOCK_KEY + ")");
			try {
				st.execute("""
						CREATE TABLE IF NOT EXISTS document_figure (
						    id            uuid PRIMARY KEY,
						    filename      varchar(255) NOT NULL,
						    page_number   integer NOT NULL,
						    figure_index  integer NOT NULL,
						    kind          varchar(40) NOT NULL,
						    title         varchar(500),
						    caption       text,
						    readable_text text,
						    mime_type     varchar(80) NOT NULL,
						    width         integer,
						    height        integer,
						    image_data    bytea NOT NULL,
						    created_at    timestamptz NOT NULL
						)
						""");
				st.execute("""
						CREATE INDEX IF NOT EXISTS idx_document_figure_file_page
						    ON document_figure (filename, page_number)
						""");
				log.info("document_figure schema is ready");
			} finally {
				st.execute("SELECT pg_advisory_unlock(" + MIGRATION_LOCK_KEY + ")");
			}
		}
	}
}
