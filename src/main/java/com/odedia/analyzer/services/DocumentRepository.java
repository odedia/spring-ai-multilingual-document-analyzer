package com.odedia.analyzer.services;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.odedia.analyzer.dto.DocumentInfo;

@Repository
public class DocumentRepository {
    private final JdbcTemplate jdbc;

    public DocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Pulls out all distinct filename values from the JSON metadata column.
     * Works whether metadata is json or jsonb (casts to jsonb if needed).
     */
    public List<DocumentInfo> findDistinctDocuments() {
        String sql = """
        SELECT DISTINCT
            metadata::jsonb ->> 'filename'  AS filename,
            metadata::jsonb ->> 'language'  AS language
        FROM vector_store
        WHERE metadata::jsonb ? 'filename'
          AND metadata::jsonb ? 'language'
        ORDER BY  -- sort in the database
            language ASC,
            filename ASC
        """;
        return jdbc.query(sql, (rs, rowNum) ->
            new DocumentInfo(
                rs.getString("filename"),
                rs.getString("language")
            )
        );
    }
}
