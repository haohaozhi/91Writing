package app.dreamwriter.search;

import app.dreamwriter.common.PageResponse;
import app.dreamwriter.project.ProjectModels.ChapterEntry;
import app.dreamwriter.search.SearchModels.SearchResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.springframework.stereotype.Service;

@Service
public class ProjectSearchService {
  private final StandardAnalyzer analyzer = new StandardAnalyzer();

  public void initialize(Path root) throws IOException {
    Files.createDirectories(indexPath(root));
    try (Directory directory = FSDirectory.open(indexPath(root));
         IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
      writer.commit();
    }
  }

  public void clear(Path root) throws IOException {
    initialize(root);
    try (Directory directory = FSDirectory.open(indexPath(root));
         IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
      writer.deleteAll();
      writer.commit();
    }
  }

  public void upsertChapter(Path root, ChapterEntry chapter, String content) throws IOException {
    upsert(
        root,
        "chapter",
        "chapter",
        chapter.id,
        chapter.title,
        chapter.path,
        chapter.updatedAt == null ? Instant.now() : chapter.updatedAt,
        content
    );
  }

  public void deleteChapter(Path root, String chapterId) throws IOException {
    delete(root, documentUid("chapter", chapterId));
  }

  public void upsertDocument(
      Path root,
      String key,
      String title,
      String path,
      String scope,
      Instant updatedAt,
      String content
  ) throws IOException {
    upsert(
        root,
        "document",
        scope == null || scope.isBlank() ? "document" : scope,
        key,
        title,
        path,
        updatedAt == null ? Instant.now() : updatedAt,
        content
    );
  }

  public PageResponse<SearchResult> search(Path root, String query, String type, int page, int size)
      throws IOException {
    initialize(root);

    String keyword = query == null ? "" : query.trim();
    int currentPage = Math.max(1, page);
    int pageSize = Math.min(Math.max(1, size), 50);
    if (keyword.isBlank()) {
      return new PageResponse<>(currentPage, pageSize, 0, List.of());
    }

    Query textQuery;
    try {
      textQuery = new MultiFieldQueryParser(new String[] {"title", "content"}, analyzer)
          .parse(QueryParser.escape(keyword));
    } catch (ParseException error) {
      throw new IllegalArgumentException("搜索关键字解析失败: " + error.getMessage(), error);
    }

    BooleanQuery.Builder builder = new BooleanQuery.Builder();
    builder.add(textQuery, BooleanClause.Occur.MUST);
    applyTypeFilter(builder, type);

    try (Directory directory = FSDirectory.open(indexPath(root))) {
      if (!DirectoryReader.indexExists(directory)) {
        return new PageResponse<>(currentPage, pageSize, 0, List.of());
      }

      try (DirectoryReader reader = DirectoryReader.open(directory)) {
        IndexSearcher searcher = new IndexSearcher(reader);
        int offset = (currentPage - 1) * pageSize;
        int limit = Math.min(Math.max(currentPage * pageSize, 20), 1000);
        TopDocs docs = searcher.search(builder.build(), limit);
        long total = docs.totalHits == null ? 0 : docs.totalHits.value();

        List<SearchResult> items = new ArrayList<>();
        ScoreDoc[] scoreDocs = docs.scoreDocs;
        for (int i = offset; i < Math.min(scoreDocs.length, offset + pageSize); i++) {
          ScoreDoc scoreDoc = scoreDocs[i];
          Document document = searcher.storedFields().document(scoreDoc.doc);
          items.add(toSearchResult(document, keyword, scoreDoc.score));
        }

        return new PageResponse<>(currentPage, pageSize, total, items);
      }
    }
  }

  private void upsert(
      Path root,
      String type,
      String scope,
      String id,
      String title,
      String path,
      Instant updatedAt,
      String content
  ) throws IOException {
    initialize(root);
    try (Directory directory = FSDirectory.open(indexPath(root));
         IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
      writer.updateDocument(new Term("uid", documentUid(type, id)),
          buildDocument(type, scope, id, title, path, updatedAt, content));
      writer.commit();
    }
  }

  private void delete(Path root, String uid) throws IOException {
    initialize(root);
    try (Directory directory = FSDirectory.open(indexPath(root));
         IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
      writer.deleteDocuments(new Term("uid", uid));
      writer.commit();
    }
  }

  private Document buildDocument(
      String type,
      String scope,
      String id,
      String title,
      String path,
      Instant updatedAt,
      String content
  ) {
    Document document = new Document();
    String normalizedTitle = title == null || title.isBlank() ? "未命名" : title.trim();
    String normalizedPath = path == null ? "" : path;
    String normalizedUpdatedAt = updatedAt == null ? Instant.now().toString() : updatedAt.toString();
    String searchableContent = searchableText(content);

    document.add(new StringField("uid", documentUid(type, id), Field.Store.NO));
    document.add(new StringField("type", type, Field.Store.YES));
    document.add(new StringField("scope", scope, Field.Store.YES));
    document.add(new StringField("id", id, Field.Store.YES));
    document.add(new StringField("path", normalizedPath, Field.Store.YES));
    document.add(new StringField("updatedAt", normalizedUpdatedAt, Field.Store.YES));
    document.add(new TextField("title", normalizedTitle, Field.Store.YES));
    document.add(new StoredField("contentStored", searchableContent));
    document.add(new TextField("content", searchableContent, Field.Store.NO));
    return document;
  }

  private SearchResult toSearchResult(Document document, String query, float score) {
    String content = document.get("contentStored");
    String updatedAtValue = document.get("updatedAt");
    Instant updatedAt = updatedAtValue == null || updatedAtValue.isBlank()
        ? Instant.now()
        : Instant.parse(updatedAtValue);

    return new SearchResult(
        document.get("type"),
        document.get("scope"),
        document.get("id"),
        document.get("title"),
        document.get("path"),
        buildSnippet(content, query),
        Math.round(score * 100.0d) / 100.0d,
        updatedAt
    );
  }

  private void applyTypeFilter(BooleanQuery.Builder builder, String type) {
    String normalized = type == null ? "all" : type.trim().toLowerCase(Locale.ROOT);
    switch (normalized) {
      case "chapter":
      case "chapters":
        builder.add(new TermQuery(new Term("type", "chapter")), BooleanClause.Occur.FILTER);
        break;
      case "document":
      case "documents":
        builder.add(new TermQuery(new Term("type", "document")), BooleanClause.Occur.FILTER);
        break;
      case "memory":
        builder.add(new TermQuery(new Term("type", "document")), BooleanClause.Occur.FILTER);
        builder.add(new TermQuery(new Term("scope", "memory")), BooleanClause.Occur.FILTER);
        break;
      case "setting":
      case "settings":
        builder.add(new TermQuery(new Term("type", "document")), BooleanClause.Occur.FILTER);
        builder.add(new TermQuery(new Term("scope", "setting")), BooleanClause.Occur.FILTER);
        break;
      default:
        break;
    }
  }

  private Path indexPath(Path root) {
    return root.resolve(".dream-writer").resolve("lucene").toAbsolutePath().normalize();
  }

  private String documentUid(String type, String id) {
    return type + ":" + id;
  }

  private String searchableText(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value
        .replaceAll("(?s)---.*?---", "")
        .replaceAll("<[^>]+>", " ")
        .replaceAll("[#>*_`\\-\\[\\]()!]", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private String buildSnippet(String content, String query) {
    String normalized = content == null ? "" : content.trim();
    if (normalized.isBlank()) {
      return "";
    }

    String compact = normalized.replaceAll("\\s+", " ");
    String lowerContent = compact.toLowerCase(Locale.ROOT);
    String lowerQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);

    int index = lowerQuery.isBlank() ? -1 : lowerContent.indexOf(lowerQuery);
    if (index < 0 && !lowerQuery.isBlank()) {
      for (String token : lowerQuery.split("\\s+")) {
        if (!token.isBlank()) {
          index = lowerContent.indexOf(token);
          if (index >= 0) {
            break;
          }
        }
      }
    }

    if (index < 0) {
      return compact.length() <= 120 ? compact : compact.substring(0, 120).trim() + "...";
    }

    int start = Math.max(0, index - 40);
    int end = Math.min(compact.length(), index + Math.max(lowerQuery.length(), 12) + 80);
    String prefix = start > 0 ? "..." : "";
    String suffix = end < compact.length() ? "..." : "";
    return prefix + compact.substring(start, end).trim() + suffix;
  }
}
