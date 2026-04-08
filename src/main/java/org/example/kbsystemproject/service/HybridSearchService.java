package org.example.kbsystemproject.service;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;

@Service
public class HybridSearchService {

    private final DatabaseClient databaseClient;

    public HybridSearchService(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /**
     * PostgreSQL Hybrid Search: Full-Text Search (TSVector approximation of BM25) + Vector Search (pgvector)
     * Requires pgvector extension and a vector column, as well as a full-text search index.
     *
     * @param query       The text query string
     * @param queryVector The embedding vector of the query
     * @param limit       Result limit
     * @return A stream of results mapped to Map
     */
    public Flux<Map<String, Object>> performHybridSearch(String query, String queryVector, int limit) {
        // A common formula for hybrid search in PostgreSQL is using Cross-Encoder or Reciprocal Rank Fusion (RRF),
        // or a weighted combination of Vector Distance and FTS Rank.
        // Here we use a weighted score (alpha).
        // e.g., FTS score uses ts_rank_cd, Vector score uses 1 - (embedding <=> filter_vector).

        String sql = """
            WITH fts_search AS (
                SELECT id, content, metadata,
                       ts_rank_cd(to_tsvector('english', content), plainto_tsquery('english', :query)) AS fts_score
                FROM vector_store
                WHERE to_tsvector('english', content) @@ plainto_tsquery('english', :query)
            ),
            vector_search AS (
                SELECT id, content, metadata,
                       1 - (embedding <=> :queryVector::vector) AS vector_score
                FROM vector_store
                ORDER BY embedding <=> :queryVector::vector
                LIMIT :limit
            )
            SELECT
                COALESCE(f.id, v.id) AS id,
                COALESCE(f.content, v.content) AS content,
                COALESCE(f.metadata, v.metadata) AS metadata,
                COALESCE(f.fts_score, 0.0) AS fts_score,
                COALESCE(v.vector_score, 0.0) AS vector_score,
                (COALESCE(f.fts_score, 0.0) * 0.3) + (COALESCE(v.vector_score, 0.0) * 0.7) AS hybrid_weight
            FROM fts_search f
            FULL OUTER JOIN vector_search v ON f.id = v.id
            ORDER BY hybrid_weight DESC
            LIMIT :limit;
            """;

        return databaseClient.sql(sql)
                .bind("query", query)
                // In R2DBC, you might need to convert queryVector string to appropriate Postgres type format
                // queryVector should be string format like: '[0.1, 0.2, ...]'
                .bind("queryVector", queryVector)
                .bind("limit", limit)
                .fetch()
                .all();
    }
}

