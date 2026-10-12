package com.agentgate.memory.repository;

import com.agentgate.memory.dto.AgentMemory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * agent_memories, in SQL: the optional pgvector column has no JPA mapping, and H2 (used by most tests)
 * has neither vectors nor this table.
 */
@Repository
public class AgentMemoryRepository {

    private static final String COLUMNS = "id, agent_id, content, source, execution_id, created_by, created_at";
    // Replaced memories are kept for the record but take no part in recall, search or listing.
    private static final String ACTIVE = "and superseded_by is null ";

    private final JdbcClient jdbc;
    private volatile Boolean vectorsSupported;

    public AgentMemoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether the database has the embedding column, i.e. pgvector was available when it was migrated. */
    public boolean supportsVectors() {
        Boolean supported = vectorsSupported;
        if (supported == null) {
            supported = jdbc.sql("""
                            select count(*) from information_schema.columns
                            where table_name = 'agent_memories' and column_name = 'embedding'""")
                    .query(Long.class).single() > 0;
            vectorsSupported = supported;
        }
        return supported;
    }

    public AgentMemory insert(String agentId, String content, String source, String executionId, String createdBy) {
        return jdbc.sql("insert into agent_memories (agent_id, content, source, execution_id, created_by) "
                        + "values (:agentId, :content, :source, :executionId, :createdBy) returning " + COLUMNS)
                .param("agentId", agentId)
                .param("content", content)
                .param("source", source)
                .param("executionId", executionId)
                .param("createdBy", createdBy)
                .query(AgentMemoryRepository::map)
                .single();
    }

    public void setEmbedding(Long id, float[] embedding, String model) {
        jdbc.sql("update agent_memories set embedding = cast(:embedding as vector), embedding_model = :model where id = :id")
                .param("embedding", literal(embedding))
                .param("model", model)
                .param("id", id)
                .update();
    }

    public List<AgentMemory> recent(String agentId, int limit) {
        return jdbc.sql("select " + COLUMNS + " from agent_memories where agent_id = :agentId " + ACTIVE
                        + "order by created_at desc, id desc limit :limit")
                .param("agentId", agentId)
                .param("limit", limit)
                .query(AgentMemoryRepository::map)
                .list();
    }

    /** Closest memories embedded with {@code model}, scored by cosine similarity (1 = same direction). */
    public List<AgentMemory> nearest(String agentId, float[] query, String model, double minScore, int limit) {
        return jdbc.sql("select " + COLUMNS + ", 1 - (embedding <=> cast(:query as vector)) as score "
                        + "from agent_memories where agent_id = :agentId " + ACTIVE + "and embedding_model = :model "
                        + "and embedding is not null and 1 - (embedding <=> cast(:query as vector)) >= :minScore "
                        + "order by embedding <=> cast(:query as vector) limit :limit")
                .param("query", literal(query))
                .param("agentId", agentId)
                .param("model", model)
                .param("minScore", minScore)
                .param("limit", limit)
                .query((rs, row) -> map(rs, row).scored(rs.getDouble("score"), "SEMANTIC"))
                .list();
    }

    /**
     * Memories containing any of the terms (case-insensitive substring, which also matches Korean words with
     * particles attached), ranked by the share of terms they contain, then newest first.
     */
    public List<AgentMemory> keyword(String agentId, List<String> terms, List<Long> excludeIds, int limit) {
        if (terms.isEmpty()) {
            return List.of();
        }
        StringBuilder matches = new StringBuilder();
        StringBuilder score = new StringBuilder();
        for (int i = 0; i < terms.size(); i++) {
            matches.append(i == 0 ? "" : " or ").append("content ilike :t").append(i);
            score.append(i == 0 ? "" : " + ").append("(case when content ilike :t").append(i).append(" then 1 else 0 end)");
        }
        String exclude = excludeIds.isEmpty() ? "" : " and id not in (:excludeIds)";
        JdbcClient.StatementSpec statement = jdbc.sql("select " + COLUMNS + ", (" + score + ") as matched "
                        + "from agent_memories where agent_id = :agentId " + ACTIVE + "and (" + matches + ")" + exclude
                        + " order by matched desc, created_at desc, id desc limit :limit")
                .param("agentId", agentId)
                .param("limit", limit);
        for (int i = 0; i < terms.size(); i++) {
            statement = statement.param("t" + i, "%" + escapeLike(terms.get(i)) + "%");
        }
        if (!excludeIds.isEmpty()) {
            statement = statement.param("excludeIds", excludeIds);
        }
        int termCount = terms.size();
        return statement
                .query((rs, row) -> map(rs, row).scored((double) rs.getInt("matched") / termCount, "KEYWORD"))
                .list();
    }

    /** An active (not replaced) memory of the agent. */
    public Optional<AgentMemory> find(String agentId, Long id) {
        return jdbc.sql("select " + COLUMNS + " from agent_memories where agent_id = :agentId " + ACTIVE + "and id = :id")
                .param("agentId", agentId)
                .param("id", id)
                .query(AgentMemoryRepository::map)
                .optional();
    }

    /** Marks {@code oldId} as replaced by {@code newId}; false if it was not an active memory of the agent. */
    public boolean supersede(String agentId, Long oldId, Long newId) {
        return jdbc.sql("update agent_memories set superseded_by = :newId, superseded_at = now() "
                        + "where agent_id = :agentId and id = :oldId " + ACTIVE)
                .param("newId", newId)
                .param("agentId", agentId)
                .param("oldId", oldId)
                .update() > 0;
    }

    public boolean delete(String agentId, Long id) {
        return jdbc.sql("delete from agent_memories where agent_id = :agentId and id = :id")
                .param("agentId", agentId)
                .param("id", id)
                .update() > 0;
    }

    /** Memories without a vector from {@code model}: never embedded, or embedded with an earlier model. */
    public List<PendingEmbedding> needingEmbedding(String model, int limit) {
        return jdbc.sql("select id, content from agent_memories "
                        + "where (embedding is null or embedding_model is distinct from :model) " + ACTIVE
                        + "order by id limit :limit")
                .param("model", model)
                .param("limit", limit)
                .query((rs, row) -> new PendingEmbedding(rs.getLong("id"), rs.getString("content")))
                .list();
    }

    public record PendingEmbedding(Long id, String content) {
    }

    private static AgentMemory map(ResultSet rs, int row) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new AgentMemory(rs.getLong("id"), rs.getString("agent_id"), rs.getString("content"),
                rs.getString("source"), rs.getString("execution_id"), rs.getString("created_by"),
                createdAt == null ? null : createdAt.toInstant(), null, null);
    }

    static String literal(float[] vector) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            text.append(i == 0 ? "" : ",").append(vector[i]);
        }
        return text.append(']').toString();
    }

    /** Text compared for exact duplicates: case, spaces and punctuation do not count. */
    public static String normalized(String content) {
        return Normalizer.normalize(content, Normalizer.Form.NFC)
                .toLowerCase()
                .replaceAll("[\\s\\p{Punct}\\p{IsPunctuation}]+", "");
    }

    static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public static List<String> terms(String query) {
        List<String> terms = new ArrayList<>();
        for (String word : query.toLowerCase().split("[\\s\\p{Punct}]+")) {
            if (word.codePointCount(0, word.length()) >= 2 && !terms.contains(word) && terms.size() < 8) {
                terms.add(word);
            }
        }
        return terms;
    }
}
