package org.example.kbsystemproject.ailearning.infrastructure.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class Bm25SearchService {

    private static final String FIELD_INDEX_ID = "index_id";
    private static final String FIELD_DOC_ID = "doc_id";
    private static final String FIELD_TEXT = "text";
    private static final String FIELD_METADATA = "metadata_json";
    private static final String FIELD_CONTENT = "content";
    private static final String[] SEARCH_FIELDS = {
            FIELD_CONTENT, "title", "filename", "source", "subject", "chapter"
    };
    private static final Set<String> NOISE_TERMS = Set.of(
            "什么", "怎么", "怎样", "如何", "为何", "为什么", "多少", "哪些", "哪个",
            "一下", "一个", "可以", "需要", "才能", "有没有", "是否", "吗", "呢", "吧",
            "请问", "帮我", "一下子", "一下下"
    );

    private final Analyzer analyzer = new SmartChineseAnalyzer();
    private final Directory directory = new ByteBuffersDirectory();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final IndexWriter indexWriter;
    private final HanlpKeywordExtractionService hanlpKeywordExtractionService;

    public Bm25SearchService(HanlpKeywordExtractionService hanlpKeywordExtractionService) throws IOException {
        this.hanlpKeywordExtractionService = hanlpKeywordExtractionService;
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setCommitOnClose(true);
        this.indexWriter = new IndexWriter(directory, config);
    }

    public synchronized void replaceAll(Collection<Document> documents) {
        try {
            indexWriter.deleteAll();
            indexDocumentsInternal(documents);
            indexWriter.commit();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to rebuild BM25 index", e);
        }
    }

    public synchronized void indexDocuments(Collection<Document> documents) {
        try {
            indexDocumentsInternal(documents);
            indexWriter.commit();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to update BM25 index", e);
        }
    }

    public synchronized boolean hasDocuments() {
        try (DirectoryReader reader = DirectoryReader.open(indexWriter)) {
            return reader.numDocs() > 0;
        } catch (IOException e) {
            log.warn("Failed to inspect BM25 index state", e);
            return false;
        }
    }

    public synchronized List<Document> search(String queryText, int topK) {
        if (queryText == null || queryText.isBlank() || topK <= 0) {
            return List.of();
        }
        try (DirectoryReader reader = DirectoryReader.open(indexWriter)) {
            if (reader.numDocs() == 0) {
                return List.of();
            }
            String keywordQuery = extractKeywordQuery(queryText);
            String actualQuery = keywordQuery.isBlank() ? normalizeQuery(queryText) : keywordQuery;
            IndexSearcher searcher = new IndexSearcher(reader);
            MultiFieldQueryParser parser = new MultiFieldQueryParser(SEARCH_FIELDS, analyzer);
            parser.setDefaultOperator(MultiFieldQueryParser.OR_OPERATOR);
            Query query = parser.parse(MultiFieldQueryParser.escape(actualQuery));
            TopDocs topDocs = searcher.search(query, topK);

            List<Document> results = new ArrayList<>(topDocs.scoreDocs.length);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                org.apache.lucene.document.Document storedDoc = searcher.storedFields().document(scoreDoc.doc);
                Map<String, Object> metadata = parseMetadata(storedDoc.get(FIELD_METADATA));
                Map<String, Object> enrichedMetadata = new LinkedHashMap<>(metadata);
                enrichedMetadata.put("score", scoreDoc.score);
                enrichedMetadata.put("retrievalSource", "bm25");
                enrichedMetadata.put("bm25RawQuery", queryText);
                enrichedMetadata.put("bm25KeywordQuery", actualQuery);

                results.add(new Document(
                        storedDoc.get(FIELD_DOC_ID),
                        storedDoc.get(FIELD_TEXT),
                        enrichedMetadata
                ));
            }
            return results;
        } catch (Exception e) {
            log.warn("BM25 search failed for query={}", queryText, e);
            return List.of();
        }
    }

    public String extractKeywordQuery(String queryText) {
        String normalized = normalizeQuery(queryText);
        if (normalized.isBlank()) {
            return "";
        }
        List<String> hanlpKeywords = hanlpKeywordExtractionService.extractKeywords(normalized);
        if (!hanlpKeywords.isEmpty()) {
            return hanlpKeywords.stream()
                    .map(String::trim)
                    .filter(token -> !token.isBlank())
                    .distinct()
                    .limit(8)
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
        }
        LinkedHashSet<String> keywords = new LinkedHashSet<>(tokenize(normalized).stream()
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .filter(token -> !NOISE_TERMS.contains(token))
                .filter(this::isKeywordCandidate)
                .limit(8)
                .toList());
        if (keywords.isEmpty()) {
            keywords.addAll(tokenize(normalized).stream()
                    .map(String::trim)
                    .filter(token -> !token.isBlank())
                    .limit(8)
                    .toList());
        }
        return String.join(" ", keywords);
    }

    private void indexDocumentsInternal(Collection<Document> documents) throws IOException {
        if (documents == null || documents.isEmpty()) {
            return;
        }
        for (Document document : documents) {
            if (document == null || document.getText() == null || document.getText().isBlank()) {
                continue;
            }
            String indexId = resolveIndexId(document);
            org.apache.lucene.document.Document luceneDocument = new org.apache.lucene.document.Document();
            luceneDocument.add(new StringField(FIELD_INDEX_ID, indexId, Field.Store.YES));
            luceneDocument.add(new StoredField(FIELD_DOC_ID, resolveDocumentId(document)));
            luceneDocument.add(new StoredField(FIELD_TEXT, document.getText()));
            luceneDocument.add(new StoredField(FIELD_METADATA, objectMapper.writeValueAsString(safeMetadata(document))));
            luceneDocument.add(new TextField(FIELD_CONTENT, document.getText(), Field.Store.NO));
            Map<String, Object> metadata = safeMetadata(document);
            addMetadataField(luceneDocument, "title", metadata.get("title"));
            addMetadataField(luceneDocument, "filename", metadata.get("filename"));
            addMetadataField(luceneDocument, "source", metadata.get("source"));
            addMetadataField(luceneDocument, "subject", metadata.get("subject"));
            addMetadataField(luceneDocument, "chapter", metadata.get("chapter"));
            indexWriter.updateDocument(new Term(FIELD_INDEX_ID, indexId), luceneDocument);
        }
    }

    private void addMetadataField(org.apache.lucene.document.Document luceneDocument, String fieldName, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isBlank()) {
            luceneDocument.add(new TextField(fieldName, text, Field.Store.NO));
        }
    }

    private String resolveIndexId(Document document) {
        String baseId = resolveDocumentId(document);
        String hash = sha1(document.getText());
        return baseId + "::" + hash;
    }

    private String resolveDocumentId(Document document) {
        if (document.getId() != null && !document.getId().isBlank()) {
            return document.getId();
        }
        Map<String, Object> metadata = safeMetadata(document);
        Object originalId = metadata.get("t2_doc_id");
        if (originalId != null) {
            return originalId.toString();
        }
        Object filename = metadata.get("filename");
        if (filename != null) {
            return filename.toString();
        }
        return sha1(document.getText());
    }

    private Map<String, Object> safeMetadata(Document document) {
        return document.getMetadata() == null ? Map.of() : document.getMetadata();
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse BM25 metadata json", e);
            return Map.of();
        }
    }

    private List<String> tokenize(String text) {
        try (TokenStream tokenStream = analyzer.tokenStream(FIELD_CONTENT, new StringReader(text))) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            List<String> tokens = new ArrayList<>();
            while (tokenStream.incrementToken()) {
                tokens.add(termAttribute.toString());
            }
            tokenStream.end();
            return tokens;
        } catch (IOException e) {
            log.warn("Failed to tokenize BM25 query", e);
            return List.of(text);
        }
    }

    private boolean isKeywordCandidate(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (token.length() > 1) {
            return true;
        }
        char ch = token.charAt(0);
        return Character.isDigit(ch) || Character.isLetter(ch);
    }

    private String normalizeQuery(String queryText) {
        return queryText == null ? "" : queryText.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String sha1(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute text hash", e);
        }
    }
}
