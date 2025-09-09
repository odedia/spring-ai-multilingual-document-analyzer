package com.odedia.analyzer.services;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class JdbcService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    public void clearVectorStore() {
        jdbcTemplate.update("TRUNCATE TABLE vector_store RESTART IDENTITY");
    }

    public int clearVectorStoreForOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            return 0;
        }
        String sql = "DELETE FROM vector_store WHERE (metadata::jsonb ->> 'owner') = ?";
        return jdbcTemplate.update(sql, owner);
    }
}
