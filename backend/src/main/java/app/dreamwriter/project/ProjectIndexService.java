package app.dreamwriter.project;

import app.dreamwriter.project.ProjectModels.ChapterEntry;
import app.dreamwriter.project.ProjectModels.ProjectBackupDetail;
import app.dreamwriter.project.ProjectModels.ProjectBackupItem;
import app.dreamwriter.project.ProjectModels.ProjectBackupSummary;
import app.dreamwriter.project.ProjectModels.ProjectDocument;
import app.dreamwriter.project.ProjectModels.ProjectFileState;
import app.dreamwriter.project.ProjectModels.ProjectSyncEvent;
import app.dreamwriter.project.ProjectModels.ProjectSyncState;
import app.dreamwriter.usage.UsageModels.ApiCallRecord;
import app.dreamwriter.usage.UsageModels.ApiCallRecordRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ProjectIndexService {
  public void initialize(Path root) throws SQLException {
    try (Connection connection = open(root); Statement statement = connection.createStatement()) {
      statement.executeUpdate("""
          create table if not exists chapters (
            id text primary key,
            title text not null,
            chapter_order integer not null,
            description text not null default '',
            status text not null,
            path text not null,
            word_count integer not null,
            updated_at text not null,
            content_text text not null default ''
          )
          """);
      addColumnIfMissing(statement, "chapters", "description", "text not null default ''");
      addColumnIfMissing(statement, "chapters", "content_text", "text not null default ''");
      statement.executeUpdate("create index if not exists idx_chapters_order on chapters(chapter_order)");
      statement.executeUpdate("create index if not exists idx_chapters_status on chapters(status)");
      statement.executeUpdate("create index if not exists idx_chapters_title on chapters(title)");
      statement.executeUpdate("""
          create table if not exists api_call_records (
            id text primary key,
            timestamp text not null,
            type text not null,
            model text not null,
            content text not null default '',
            response text not null default '',
            input_tokens integer not null default 0,
            output_tokens integer not null default 0,
            total_tokens integer not null default 0,
            cost real not null default 0,
            status text not null,
            novel_id text not null default '',
            novel_title text not null default ''
          )
          """);
      statement.executeUpdate(
          "create index if not exists idx_api_call_records_timestamp on api_call_records(timestamp desc)"
      );
      statement.executeUpdate("""
          create table if not exists sync_state (
            id integer primary key check (id = 1),
            fingerprint text,
            latest_file_modified_at text,
            scanned_at text not null,
            watch_revision integer not null default 0,
            change_detected_at text,
            external_changes_pending integer not null default 0,
            changed_paths text not null default ''
          )
          """);
      statement.executeUpdate("""
          create table if not exists sync_events (
            id integer primary key autoincrement,
            event_type text not null,
            fingerprint text,
            latest_file_modified_at text,
            scanned_at text not null,
            watch_revision integer not null default 0,
            change_detected_at text,
            external_changes_pending integer not null default 0,
            changed_paths text not null default '',
            created_at text not null
          )
          """);
      statement.executeUpdate(
          "create index if not exists idx_sync_events_created_at on sync_events(created_at desc)"
      );
      statement.executeUpdate("""
          create table if not exists file_state (
            relative_path text primary key,
            file_type text not null,
            content_hash text not null,
            size integer not null,
            modified_at text,
            scanned_at text not null,
            status text not null
          )
          """);
      statement.executeUpdate("create index if not exists idx_file_state_type on file_state(file_type)");
      statement.executeUpdate("create index if not exists idx_file_state_status on file_state(status)");
      statement.executeUpdate("""
          create table if not exists backup_sets (
            id text primary key,
            label text not null,
            reason text not null default '',
            project_path text not null,
            backup_path text not null,
            project_id text not null default '',
            project_title text not null default '',
            sync_fingerprint text not null default '',
            item_count integer not null default 0,
            total_bytes integer not null default 0,
            created_at text not null
          )
          """);
      statement.executeUpdate(
          "create index if not exists idx_backup_sets_created_at on backup_sets(created_at desc)"
      );
      statement.executeUpdate("""
          create table if not exists backup_items (
            backup_id text not null,
            relative_path text not null,
            item_type text not null,
            size integer not null default 0,
            content_hash text not null default '',
            modified_at text,
            primary key (backup_id, relative_path)
          )
          """);
      statement.executeUpdate(
          "create index if not exists idx_backup_items_backup_id on backup_items(backup_id)"
      );
    }
  }

  public void rebuild(Path root, ProjectDocument project) throws SQLException {
    initialize(root);
    clearChapters(root);
    for (ChapterEntry chapter : project.chapters) {
      upsertChapter(root, chapter, "");
    }
  }

  public void clearChapters(Path root) throws SQLException {
    initialize(root);
    try (Connection connection = open(root); Statement statement = connection.createStatement()) {
      statement.executeUpdate("delete from chapters");
    }
  }

  public void upsertSyncState(Path root, ProjectSyncState syncState) throws SQLException {
    initialize(root);
    try (Connection connection = open(root);
         PreparedStatement statement = connection.prepareStatement("""
             insert into sync_state(
               id, fingerprint, latest_file_modified_at, scanned_at,
               watch_revision, change_detected_at, external_changes_pending, changed_paths
             )
             values (1, ?, ?, ?, ?, ?, ?, ?)
             on conflict(id) do update set
               fingerprint = excluded.fingerprint,
               latest_file_modified_at = excluded.latest_file_modified_at,
               scanned_at = excluded.scanned_at,
               watch_revision = excluded.watch_revision,
               change_detected_at = excluded.change_detected_at,
               external_changes_pending = excluded.external_changes_pending,
               changed_paths = excluded.changed_paths
             """)) {
      bindSyncState(statement, syncState, false);
      statement.executeUpdate();
    }
  }

  public void recordSyncEvent(Path root, String eventType, ProjectSyncState syncState) throws SQLException {
    initialize(root);
    try (Connection connection = open(root);
         PreparedStatement statement = connection.prepareStatement("""
             insert into sync_events(
               event_type, fingerprint, latest_file_modified_at, scanned_at,
               watch_revision, change_detected_at, external_changes_pending, changed_paths, created_at
             )
             values (?, ?, ?, ?, ?, ?, ?, ?, ?)
             """)) {
      statement.setString(1, eventType == null || eventType.isBlank() ? "unknown" : eventType.trim());
      bindSyncState(statement, syncState, true);
      statement.executeUpdate();
    }
  }

  public List<ProjectSyncEvent> listSyncEvents(Path root, int limit) throws SQLException {
    initialize(root);
    int size = Math.min(Math.max(1, limit), 100);
    List<ProjectSyncEvent> items = new ArrayList<>();
    try (Connection connection = open(root);
         PreparedStatement statement = connection.prepareStatement("""
             select id, event_type, fingerprint, latest_file_modified_at, scanned_at,
                    watch_revision, change_detected_at, external_changes_pending, changed_paths, created_at
             from sync_events
             order by created_at desc, id desc
             limit ?
             """)) {
      statement.setInt(1, size);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          items.add(new ProjectSyncEvent(
              rs.getLong("id"),
              rs.getString("event_type"),
              rs.getString("fingerprint"),
              parseInstant(rs.getString("latest_file_modified_at")),
              parseInstant(rs.getString("scanned_at")),
              rs.getLong("watch_revision"),
              parseInstant(rs.getString("change_detected_at")),
              rs.getInt("external_changes_pending") != 0,
              splitChangedPaths(rs.getString("changed_paths")),
              parseInstant(rs.getString("created_at"))
          ));
        }
      }
    }
    return items;
  }

  public void replaceFileStates(Path root, List<ProjectFileState> files) throws SQLException {
    initialize(root);
    try (Connection connection = open(root)) {
      connection.setAutoCommit(false);
      try (Statement clear = connection.createStatement();
           PreparedStatement statement = connection.prepareStatement("""
               insert into file_state(
                 relative_path, file_type, content_hash, size, modified_at, scanned_at, status
               )
               values (?, ?, ?, ?, ?, ?, ?)
               """)) {
        clear.executeUpdate("delete from file_state");
        for (ProjectFileState file : files == null ? List.<ProjectFileState>of() : files) {
          statement.setString(1, file.relativePath());
          statement.setString(2, file.fileType());
          statement.setString(3, file.contentHash());
          statement.setLong(4, file.size());
          statement.setString(5, file.modifiedAt() == null ? null : file.modifiedAt().toString());
          statement.setString(6, file.scannedAt() == null ? Instant.now().toString() : file.scannedAt().toString());
          statement.setString(7, file.status() == null || file.status().isBlank() ? "tracked" : file.status());
          statement.addBatch();
        }
        statement.executeBatch();
        connection.commit();
      } catch (SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  public List<ProjectFileState> listFileStates(Path root) throws SQLException {
    initialize(root);
    List<ProjectFileState> items = new ArrayList<>();
    try (Connection connection = open(root);
         PreparedStatement statement = connection.prepareStatement("""
             select relative_path, file_type, content_hash, size, modified_at, scanned_at, status
             from file_state
             order by relative_path asc
             """);
         ResultSet rs = statement.executeQuery()) {
      while (rs.next()) {
        items.add(new ProjectFileState(
            rs.getString("relative_path"),
            rs.getString("file_type"),
            rs.getString("content_hash"),
            rs.getLong("size"),
            parseInstant(rs.getString("modified_at")),
            parseInstant(rs.getString("scanned_at")),
            rs.getString("status")
        ));
      }
    }
    return items;
  }

  public void recordBackup(Path root, ProjectBackupSummary summary, List<ProjectBackupItem> items)
      throws SQLException {
    initialize(root);
    try (Connection connection = open(root)) {
      connection.setAutoCommit(false);
      try (PreparedStatement backupStatement = connection.prepareStatement("""
               insert into backup_sets(
                 id, label, reason, project_path, backup_path, project_id, project_title,
                 sync_fingerprint, item_count, total_bytes, created_at
               )
               values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               on conflict(id) do update set
                 label = excluded.label,
                 reason = excluded.reason,
                 project_path = excluded.project_path,
                 backup_path = excluded.backup_path,
                 project_id = excluded.project_id,
                 project_title = excluded.project_title,
                 sync_fingerprint = excluded.sync_fingerprint,
                 item_count = excluded.item_count,
                 total_bytes = excluded.total_bytes,
                 created_at = excluded.created_at
               """);
           PreparedStatement clearItems =
               connection.prepareStatement("delete from backup_items where backup_id = ?");
           PreparedStatement itemStatement = connection.prepareStatement("""
               insert into backup_items(
                 backup_id, relative_path, item_type, size, content_hash, modified_at
               )
               values (?, ?, ?, ?, ?, ?)
               """)) {
        backupStatement.setString(1, summary.id());
        backupStatement.setString(2, summary.label());
        backupStatement.setString(3, summary.reason() == null ? "" : summary.reason());
        backupStatement.setString(4, summary.projectPath());
        backupStatement.setString(5, summary.backupPath());
        backupStatement.setString(6, summary.projectId() == null ? "" : summary.projectId());
        backupStatement.setString(7, summary.projectTitle() == null ? "" : summary.projectTitle());
        backupStatement.setString(8, summary.syncFingerprint() == null ? "" : summary.syncFingerprint());
        backupStatement.setInt(9, summary.itemCount());
        backupStatement.setLong(10, summary.totalBytes());
        backupStatement.setString(11, summary.createdAt() == null ? Instant.now().toString() : summary.createdAt().toString());
        backupStatement.executeUpdate();

        clearItems.setString(1, summary.id());
        clearItems.executeUpdate();

        for (ProjectBackupItem item : items == null ? List.<ProjectBackupItem>of() : items) {
          itemStatement.setString(1, summary.id());
          itemStatement.setString(2, item.relativePath());
          itemStatement.setString(3, item.itemType());
          itemStatement.setLong(4, item.size());
          itemStatement.setString(5, item.contentHash() == null ? "" : item.contentHash());
          itemStatement.setString(6, item.modifiedAt() == null ? null : item.modifiedAt().toString());
          itemStatement.addBatch();
        }
        itemStatement.executeBatch();
        connection.commit();
      } catch (SQLException error) {
        connection.rollback();
        throw error;
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  public List<ProjectBackupSummary> listBackups(Path root, int limit) throws SQLException {
    initialize(root);
    int size = Math.min(Math.max(1, limit), 200);
    List<ProjectBackupSummary> items = new ArrayList<>();
    try (Connection connection = open(root);
         PreparedStatement statement = connection.prepareStatement("""
             select id, label, reason, project_path, backup_path, project_id, project_title,
                    sync_fingerprint, item_count, total_bytes, created_at
             from backup_sets
             order by created_at desc, id desc
             limit ?
             """)) {
      statement.setInt(1, size);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          items.add(toBackupSummary(rs));
        }
      }
    }
    return items;
  }

  public ProjectBackupDetail readBackup(Path root, String backupId) throws SQLException {
    initialize(root);
    ProjectBackupSummary summary = null;
    List<ProjectBackupItem> items = new ArrayList<>();
    try (Connection connection = open(root);
         PreparedStatement backupStatement = connection.prepareStatement("""
             select id, label, reason, project_path, backup_path, project_id, project_title,
                    sync_fingerprint, item_count, total_bytes, created_at
             from backup_sets
             where id = ?
             """);
         PreparedStatement itemStatement = connection.prepareStatement("""
             select backup_id, relative_path, item_type, size, content_hash, modified_at
             from backup_items
             where backup_id = ?
             order by relative_path asc
             """)) {
      backupStatement.setString(1, backupId);
      try (ResultSet rs = backupStatement.executeQuery()) {
        if (rs.next()) {
          summary = toBackupSummary(rs);
        }
      }
      if (summary == null) {
        return null;
      }

      itemStatement.setString(1, backupId);
      try (ResultSet rs = itemStatement.executeQuery()) {
        while (rs.next()) {
          items.add(new ProjectBackupItem(
              rs.getString("backup_id"),
              rs.getString("relative_path"),
              rs.getString("item_type"),
              rs.getLong("size"),
              rs.getString("content_hash"),
              parseInstant(rs.getString("modified_at"))
          ));
        }
      }
    }
    return new ProjectBackupDetail(summary, items);
  }

  public void upsertChapter(Path root, ChapterEntry chapter, String content) throws SQLException {
    initialize(root);
    try (Connection connection = open(root);
         PreparedStatement statement = connection.prepareStatement("""
             insert into chapters(id, title, chapter_order, description, status, path, word_count, updated_at, content_text)
             values (?, ?, ?, ?, ?, ?, ?, ?, ?)
             on conflict(id) do update set
               title = excluded.title,
               chapter_order = excluded.chapter_order,
               description = excluded.description,
               status = excluded.status,
               path = excluded.path,
               word_count = excluded.word_count,
               updated_at = excluded.updated_at,
               content_text = excluded.content_text
             """)) {
      statement.setString(1, chapter.id);
      statement.setString(2, chapter.title);
      statement.setInt(3, chapter.order);
      statement.setString(4, chapter.description == null ? "" : chapter.description);
      statement.setString(5, chapter.status);
      statement.setString(6, chapter.path);
      statement.setInt(7, chapter.wordCount);
      statement.setString(8, chapter.updatedAt == null ? Instant.now().toString() : chapter.updatedAt.toString());
      statement.setString(9, searchableText(content));
      statement.executeUpdate();
    }
  }

  public void deleteChapter(Path root, String id) throws SQLException {
    initialize(root);
    try (Connection connection = open(root);
         PreparedStatement statement = connection.prepareStatement("delete from chapters where id = ?")) {
      statement.setString(1, id);
      statement.executeUpdate();
    }
  }

  public ChapterPage queryChapters(Path root, int page, int size, String keyword, String status) throws SQLException {
    initialize(root);
    int currentPage = Math.max(1, page);
    int pageSize = Math.min(Math.max(1, size), 100);
    String filter = buildFilter(keyword, status);

    long total;
    try (Connection connection = open(root);
         PreparedStatement statement = connection.prepareStatement("select count(*) from chapters " + filter)) {
      bindFilter(statement, keyword, status);
      try (ResultSet rs = statement.executeQuery()) {
        total = rs.next() ? rs.getLong(1) : 0;
      }
    }

    List<IndexedChapter> items = new ArrayList<>();
    try (Connection connection = open(root);
         PreparedStatement statement = connection.prepareStatement(
             "select id, title, chapter_order, description, status, path, word_count, updated_at from chapters "
                 + filter
                 + " order by chapter_order asc limit ? offset ?")) {
      int index = bindFilter(statement, keyword, status);
      statement.setInt(index++, pageSize);
      statement.setInt(index, (currentPage - 1) * pageSize);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          items.add(new IndexedChapter(
              rs.getString("id"),
              rs.getString("title"),
              rs.getInt("chapter_order"),
              rs.getString("description"),
              rs.getString("status"),
              rs.getString("path"),
              rs.getInt("word_count"),
              Instant.parse(rs.getString("updated_at"))
          ));
        }
      }
    }

    return new ChapterPage(currentPage, pageSize, total, items);
  }

  public void recordApiCall(Path root, ApiCallRecordRequest request) throws SQLException {
    initialize(root);
    int inputTokens = request.inputTokens() == null ? 0 : request.inputTokens();
    int outputTokens = request.outputTokens() == null ? 0 : request.outputTokens();
    int totalTokens = request.totalTokens() == null
        ? inputTokens + outputTokens
        : request.totalTokens();

    try (Connection connection = open(root);
         PreparedStatement statement = connection.prepareStatement("""
             insert into api_call_records(
               id, timestamp, type, model, content, response,
               input_tokens, output_tokens, total_tokens, cost, status, novel_id, novel_title
             )
             values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
             on conflict(id) do update set
               timestamp = excluded.timestamp,
               type = excluded.type,
               model = excluded.model,
               content = excluded.content,
               response = excluded.response,
               input_tokens = excluded.input_tokens,
               output_tokens = excluded.output_tokens,
               total_tokens = excluded.total_tokens,
               cost = excluded.cost,
               status = excluded.status,
               novel_id = excluded.novel_id,
               novel_title = excluded.novel_title
             """)) {
      statement.setString(1, request.id());
      statement.setString(2, request.timestamp());
      statement.setString(3, request.type());
      statement.setString(4, request.model());
      statement.setString(5, request.content() == null ? "" : request.content());
      statement.setString(6, request.response() == null ? "" : request.response());
      statement.setInt(7, inputTokens);
      statement.setInt(8, outputTokens);
      statement.setInt(9, totalTokens);
      statement.setDouble(10, request.cost() == null ? 0D : request.cost());
      statement.setString(11, request.status());
      statement.setString(12, request.novelId() == null ? "" : request.novelId());
      statement.setString(13, request.novelTitle() == null ? "" : request.novelTitle());
      statement.executeUpdate();
    }
  }

  public List<ApiCallRecord> listApiCallRecords(Path root) throws SQLException {
    initialize(root);
    List<ApiCallRecord> items = new ArrayList<>();
    try (Connection connection = open(root);
         PreparedStatement statement = connection.prepareStatement("""
             select id, timestamp, type, model, content, response,
                    input_tokens, output_tokens, total_tokens, cost, status, novel_id, novel_title
             from api_call_records
             order by timestamp desc
             limit 2000
             """);
         ResultSet rs = statement.executeQuery()) {
      while (rs.next()) {
        items.add(new ApiCallRecord(
            rs.getString("id"),
            rs.getString("timestamp"),
            rs.getString("type"),
            rs.getString("model"),
            rs.getString("content"),
            rs.getString("response"),
            rs.getInt("input_tokens"),
            rs.getInt("output_tokens"),
            rs.getInt("total_tokens"),
            rs.getDouble("cost"),
            rs.getString("status"),
            rs.getString("novel_id"),
            rs.getString("novel_title")
        ));
      }
    }
    return items;
  }

  private Connection open(Path root) throws SQLException {
    Path dbPath = root.resolve(".dream-writer/index.db").toAbsolutePath().normalize();
    try {
      Files.createDirectories(dbPath.getParent());
    } catch (IOException error) {
      throw new SQLException("无法创建项目数据库目录: " + dbPath.getParent(), error);
    }
    return DriverManager.getConnection("jdbc:sqlite:" + dbPath);
  }

  private String buildFilter(String keyword, String status) {
    List<String> filters = new ArrayList<>();
    if (keyword != null && !keyword.isBlank()) {
      filters.add("(title like ? or description like ? or content_text like ?)");
    }
    if (status != null && !status.isBlank() && !"all".equals(status)) {
      filters.add("status = ?");
    }
    return filters.isEmpty() ? "" : "where " + String.join(" and ", filters);
  }

  private int bindFilter(PreparedStatement statement, String keyword, String status) throws SQLException {
    int index = 1;
    if (keyword != null && !keyword.isBlank()) {
      String like = "%" + keyword.trim() + "%";
      statement.setString(index++, like);
      statement.setString(index++, like);
      statement.setString(index++, like);
    }
    if (status != null && !status.isBlank() && !"all".equals(status)) {
      statement.setString(index++, status.trim());
    }
    return index;
  }

  private void bindSyncState(PreparedStatement statement, ProjectSyncState syncState, boolean includeCreatedAt)
      throws SQLException {
    ProjectSyncState value = syncState == null
        ? new ProjectSyncState(null, null, Instant.now(), 0L, null, false, List.of())
        : syncState;
    int index = 1;
    if (includeCreatedAt) {
      index = 2;
    }
    statement.setString(index++, value.fingerprint());
    statement.setString(index++, value.latestFileModifiedAt() == null ? null : value.latestFileModifiedAt().toString());
    statement.setString(index++, value.scannedAt() == null ? Instant.now().toString() : value.scannedAt().toString());
    statement.setLong(index++, value.watchRevision());
    statement.setString(index++, value.changeDetectedAt() == null ? null : value.changeDetectedAt().toString());
    statement.setInt(index++, value.externalChangesPending() ? 1 : 0);
    statement.setString(index, String.join("\n", value.changedPaths() == null ? List.of() : value.changedPaths()));
    if (includeCreatedAt) {
      statement.setString(index + 1, Instant.now().toString());
    }
  }

  private void addColumnIfMissing(Statement statement, String table, String column, String definition)
      throws SQLException {
    try {
      statement.executeUpdate("alter table " + table + " add column " + column + " " + definition);
    } catch (SQLException error) {
      if (!error.getMessage().toLowerCase().contains("duplicate column")) {
        throw error;
      }
    }
  }

  private String searchableText(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value
        .replaceAll("(?s)---.*?---", "")
        .replaceAll("<[^>]+>", "")
        .replaceAll("[#>*_`\\-\\[\\]()!]", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private Instant parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return Instant.parse(value);
  }

  private List<String> splitChangedPaths(String value) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return List.of(value.split("\\n")).stream()
        .map(String::trim)
        .filter(item -> !item.isBlank())
        .toList();
  }

  private ProjectBackupSummary toBackupSummary(ResultSet rs) throws SQLException {
    return new ProjectBackupSummary(
        rs.getString("id"),
        rs.getString("label"),
        rs.getString("reason"),
        rs.getString("project_path"),
        rs.getString("backup_path"),
        rs.getString("project_id"),
        rs.getString("project_title"),
        rs.getString("sync_fingerprint"),
        rs.getInt("item_count"),
        rs.getLong("total_bytes"),
        parseInstant(rs.getString("created_at"))
    );
  }

  public record IndexedChapter(
      String id,
      String title,
      int order,
      String description,
      String status,
      String path,
      int wordCount,
      Instant updatedAt
  ) {
  }

  public record ChapterPage(
      int page,
      int size,
      long total,
      List<IndexedChapter> items
  ) {
  }
}
