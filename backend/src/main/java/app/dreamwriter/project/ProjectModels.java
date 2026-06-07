package app.dreamwriter.project;

import app.dreamwriter.chapter.ChapterModels.ChapterDetail;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ProjectModels {
  private ProjectModels() {
  }

  public record CreateProjectRequest(
      String basePath,
      String title,
      String genre
  ) {
  }

  public record OpenProjectRequest(String path) {
  }

  public record ProjectPathsRequest(List<String> paths) {
  }

  public record ProjectRegistryProject(
      String rootPath,
      String title,
      String source,
      Instant registeredAt,
      Instant lastOpenedAt
  ) {
  }

  public record ProjectRegistrySnapshot(
      Map<String, ProjectRegistryProject> projects,
      Map<String, Object> legacyBindings
  ) {
  }

  public record CreateProjectBackupRequest(
      String projectPath,
      String label,
      String reason
  ) {
  }

  public record RestoreProjectBackupRequest(
      String projectPath,
      String backupId
  ) {
  }

  public record UpdateProjectMetadataRequest(
      String projectPath,
      String title,
      String genre,
      String status,
      String description,
      String cover,
      List<String> tags,
      String genrePrompt,
      String legacyNovelId,
      String legacyNovelTitle,
      Instant legacyImportedAt,
      Instant legacyUpdatedAt,
      Map<String, String> legacyChapterMapping
  ) {
  }

  public record ProjectSyncState(
      String fingerprint,
      Instant latestFileModifiedAt,
      Instant scannedAt,
      long watchRevision,
      Instant changeDetectedAt,
      boolean externalChangesPending,
      List<String> changedPaths
  ) {
  }

  public record ProjectSyncEvent(
      long id,
      String eventType,
      String fingerprint,
      Instant latestFileModifiedAt,
      Instant scannedAt,
      long watchRevision,
      Instant changeDetectedAt,
      boolean externalChangesPending,
      List<String> changedPaths,
      Instant createdAt
  ) {
  }

  public record ProjectFileState(
      String relativePath,
      String fileType,
      String contentHash,
      long size,
      Instant modifiedAt,
      Instant scannedAt,
      String status
  ) {
  }

  public record ProjectConflict(
      String relativePath,
      String fileType,
      String reason,
      Instant detectedAt
  ) {
  }

  public record ProjectSyncStatus(
      ProjectState projectState,
      String syncStatus,
      int trackedFileCount,
      int conflictCount,
      List<ProjectFileState> files,
      List<ProjectConflict> conflicts
  ) {
  }

  public record ProjectNovelSummary(
      String rootPath,
      String indexDbPath,
      String projectId,
      String title,
      String genre,
      String status,
      String description,
      String cover,
      List<String> tags,
      String genrePrompt,
      List<ChapterEntry> chapters,
      int chapterCount,
      int wordCount,
      Instant createdAt,
      Instant updatedAt,
      Instant lastOpenedAt,
      String legacyNovelId,
      String legacyNovelTitle,
      Instant legacyImportedAt,
      Instant legacyUpdatedAt,
      Map<String, String> legacyChapterMapping,
      String syncStatus,
      Instant lastSyncedAt,
      long watchRevision,
      boolean externalChangesPending,
      List<String> changedPaths,
      String syncFingerprint,
      int trackedFileCount,
      int conflictCount,
      String latestTrackedFile
  ) {
  }

  public record ProjectWriterDocument(
      String key,
      String title,
      String path,
      String content
  ) {
  }

  public record ProjectWriterView(
      ProjectState projectState,
      List<ChapterDetail> chapters,
      Map<String, ProjectWriterDocument> documents
  ) {
  }

  public record ProjectBackupSummary(
      String id,
      String label,
      String reason,
      String projectPath,
      String backupPath,
      String projectId,
      String projectTitle,
      String syncFingerprint,
      int itemCount,
      long totalBytes,
      Instant createdAt
  ) {
  }

  public record ProjectBackupItem(
      String backupId,
      String relativePath,
      String itemType,
      long size,
      String contentHash,
      Instant modifiedAt
  ) {
  }

  public record ProjectBackupDetail(
      ProjectBackupSummary summary,
      List<ProjectBackupItem> items
  ) {
  }

  public record ProjectBackupDiff(
      ProjectBackupSummary summary,
      int changedCount,
      int unchangedCount,
      int backupOnlyCount,
      int currentOnlyCount,
      List<ProjectBackupDiffItem> items
  ) {
  }

  public record ProjectBackupDiffItem(
      String relativePath,
      String itemType,
      String status,
      long backupSize,
      long currentSize,
      String backupHash,
      String currentHash,
      Instant backupModifiedAt,
      Instant currentModifiedAt,
      int addedLines,
      int removedLines,
      List<String> previewLines
  ) {
  }

  public record ProjectState(
      String rootPath,
      String indexDbPath,
      ProjectDocument project,
      ProjectSyncState syncState
  ) {
  }

  public static class ProjectDocument {
    public int schemaVersion = 1;
    public String id;
    public String title;
    public String genre;
    public String status = "writing";
    public String description = "";
    public String cover = "";
    public List<String> tags = new ArrayList<>();
    public String genrePrompt = "";
    public String legacyNovelId;
    public String legacyNovelTitle;
    public Instant legacyImportedAt;
    public Instant legacyUpdatedAt;
    public Map<String, String> legacyChapterMapping;
    public Instant createdAt;
    public Instant updatedAt;
    public Instant lastOpenedAt;
    public List<ChapterEntry> chapters = new ArrayList<>();
    public Map<String, String> settings;
    public Map<String, String> memory;
  }

  public static class ChapterEntry {
    public String id;
    public String title;
    public int order;
    public String description = "";
    public String status = "draft";
    public String path;
    public int wordCount;
    public Instant createdAt;
    public Instant updatedAt;
  }
}
