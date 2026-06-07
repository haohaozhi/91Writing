package app.dreamwriter.chapter;

import java.time.Instant;

public final class ChapterModels {
  private ChapterModels() {
  }

  public record CreateChapterRequest(
      String projectPath,
      String title,
      String description,
      String status
  ) {
  }

  public record UpdateChapterRequest(
      String projectPath,
      String title,
      String description,
      String status,
      String content
  ) {
  }

  public record ApplyChapterContentRequest(
      String projectPath,
      String title,
      String description,
      String status,
      String content,
      String backupLabel,
      String backupReason
  ) {
  }

  public record ApplyChapterContentResult(
      ChapterDetail chapter,
      app.dreamwriter.project.ProjectModels.ProjectBackupDetail backup
  ) {
  }

  public record ChapterSummary(
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

  public record ChapterDetail(
      String id,
      String title,
      int order,
      String description,
      String status,
      String path,
      int wordCount,
      Instant createdAt,
      Instant updatedAt,
      String content
  ) {
  }
}
