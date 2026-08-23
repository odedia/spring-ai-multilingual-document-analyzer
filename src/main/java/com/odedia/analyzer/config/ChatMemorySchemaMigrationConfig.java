package com.odedia.analyzer.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.chat.memory.repository.jdbc.autoconfigure.JdbcChatMemoryRepositoryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Spring AI 1.x created SPRING_AI_CHAT_MEMORY without sequence_id. Spring AI 2.0
 * requires that column. initialize-schema only runs CREATE TABLE IF NOT EXISTS,
 * so existing customer databases are left on the old shape.
 * <p>
 * Idempotent on every boot. Concurrent instances (rolling or all-at-once
 * restage) are serialized with a session-level PostgreSQL advisory lock so only
 * one instance performs DDL; the others wait, then observe the finished schema.
 * All lock and SQL work uses a single pooled connection so the lock is not left
 * held on a different connection.
 */
@Configuration
@AutoConfigureBefore(JdbcChatMemoryRepositoryAutoConfiguration.class)
public class ChatMemorySchemaMigrationConfig {

	private static final Logger log = LoggerFactory.getLogger(ChatMemorySchemaMigrationConfig.class);

	/** Stable key so every app instance contends on the same lock. */
	private static final long MIGRATION_LOCK_KEY = 872_314_001L;

	@Bean
	Object chatMemorySchemaMigration(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.execute((Connection connection) -> {
			migrateIfNeeded(connection);
			return null;
		});
		return new Object();
	}

	private static void migrateIfNeeded(Connection connection) throws SQLException {
		try (Statement st = connection.createStatement()) {
			if (!tableExists(st)) {
				return;
			}

			st.execute("SELECT pg_advisory_lock(" + MIGRATION_LOCK_KEY + ")");
			try {
				if (!tableExists(st)) {
					return;
				}

				boolean addedColumn = false;
				if (!hasSequenceId(st)) {
					log.info("Migrating SPRING_AI_CHAT_MEMORY: adding sequence_id column for Spring AI 2.0");
					st.execute("ALTER TABLE SPRING_AI_CHAT_MEMORY ADD COLUMN IF NOT EXISTS sequence_id BIGINT");
					addedColumn = true;
				}

				if (sequenceIdNullable(st)) {
					st.execute("""
							WITH ordered AS (
							    SELECT ctid,
							           ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY "timestamp") - 1 AS seq
							    FROM SPRING_AI_CHAT_MEMORY
							)
							UPDATE SPRING_AI_CHAT_MEMORY t
							SET sequence_id = o.seq
							FROM ordered o
							WHERE t.ctid = o.ctid
							  AND t.sequence_id IS NULL
							""");
					st.execute("ALTER TABLE SPRING_AI_CHAT_MEMORY ALTER COLUMN sequence_id SET NOT NULL");
					addedColumn = true;
				}

				st.execute("""
						CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_SEQUENCE_ID_IDX
						ON SPRING_AI_CHAT_MEMORY(conversation_id, sequence_id)
						""");

				if (addedColumn) {
					log.info("SPRING_AI_CHAT_MEMORY migration complete");
				}
			}
			finally {
				st.execute("SELECT pg_advisory_unlock(" + MIGRATION_LOCK_KEY + ")");
			}
		}
	}

	private static boolean tableExists(Statement st) throws SQLException {
		return queryBoolean(st, """
				SELECT EXISTS (
				    SELECT 1 FROM information_schema.tables
				    WHERE table_schema = current_schema()
				      AND table_name = 'spring_ai_chat_memory'
				)
				""");
	}

	private static boolean hasSequenceId(Statement st) throws SQLException {
		return queryBoolean(st, """
				SELECT EXISTS (
				    SELECT 1 FROM information_schema.columns
				    WHERE table_schema = current_schema()
				      AND table_name = 'spring_ai_chat_memory'
				      AND column_name = 'sequence_id'
				)
				""");
	}

	private static boolean sequenceIdNullable(Statement st) throws SQLException {
		return queryBoolean(st, """
				SELECT COALESCE((
				    SELECT is_nullable = 'YES'
				    FROM information_schema.columns
				    WHERE table_schema = current_schema()
				      AND table_name = 'spring_ai_chat_memory'
				      AND column_name = 'sequence_id'
				), FALSE)
				""");
	}

	private static boolean queryBoolean(Statement st, String sql) throws SQLException {
		try (ResultSet rs = st.executeQuery(sql)) {
			return rs.next() && rs.getBoolean(1);
		}
	}

}
