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
     * Pulls out distinct filename/language for a specific owner from the JSON metadata column.
     */
    public List<DocumentInfo> findDistinctDocumentsByOwner(String owner) {
        String sql = """
        SELECT DISTINCT
            metadata::jsonb ->> 'filename'  AS filename,
            metadata::jsonb ->> 'language'  AS language
        FROM vector_store
        WHERE jsonb_exists(metadata::jsonb, 'filename')
          AND jsonb_exists(metadata::jsonb, 'language')
          AND metadata::jsonb ->> 'owner' = ?
        ORDER BY
            language ASC,
            filename ASC
        """;
        return jdbc.query(sql, (rs, rowNum) ->
            new DocumentInfo(
                rs.getString("filename"),
                rs.getString("language")
            ), owner
        );
    }
}
