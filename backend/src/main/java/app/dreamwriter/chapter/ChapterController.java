package app.dreamwriter.chapter;

import app.dreamwriter.chapter.ChapterModels.ChapterDetail;
import app.dreamwriter.chapter.ChapterModels.ChapterSummary;
import app.dreamwriter.chapter.ChapterModels.ApplyChapterContentRequest;
import app.dreamwriter.chapter.ChapterModels.ApplyChapterContentResult;
import app.dreamwriter.chapter.ChapterModels.CreateChapterRequest;
import app.dreamwriter.chapter.ChapterModels.UpdateChapterRequest;
import app.dreamwriter.common.PageResponse;
import app.dreamwriter.project.ProjectModels.ProjectBackupDetail;
import app.dreamwriter.project.ProjectService;
import java.io.IOException;
import java.sql.SQLException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chapters")
public class ChapterController {
  private final ProjectService projectService;

  public ChapterController(ProjectService projectService) {
    this.projectService = projectService;
  }

  @GetMapping
  public PageResponse<ChapterSummary> list(
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "all") String status,
      @RequestParam(required = false) String projectPath
  ) throws IOException, SQLException {
    return projectService.listChapters(page, size, keyword, status, projectPath);
  }

  @GetMapping("/{id}")
  public ChapterDetail get(
      @PathVariable String id,
      @RequestParam(required = false) String projectPath
  ) throws IOException, SQLException {
    return projectService.readChapter(id, projectPath);
  }

  @PostMapping
  public ChapterDetail create(@RequestBody CreateChapterRequest request) throws IOException, SQLException {
    return projectService.createChapter(
        request.projectPath(),
        request.title(),
        request.description(),
        request.status()
    );
  }

  @PostMapping("/{id}/snapshot")
  public ProjectBackupDetail snapshot(
      @PathVariable String id,
      @RequestParam(required = false) String projectPath
  ) throws IOException, SQLException {
    return projectService.createChapterSnapshot(id, projectPath);
  }

  @PostMapping("/{id}/apply")
  public ApplyChapterContentResult apply(
      @PathVariable String id,
      @RequestBody ApplyChapterContentRequest request
  ) throws IOException, SQLException {
    return projectService.applyChapterContent(id, request);
  }

  @PutMapping("/{id}")
  public ChapterDetail update(@PathVariable String id, @RequestBody UpdateChapterRequest request)
      throws IOException, SQLException {
    return projectService.updateChapter(id, request);
  }

  @DeleteMapping("/{id}")
  public void delete(
      @PathVariable String id,
      @RequestParam(required = false) String projectPath
  ) throws IOException, SQLException {
    projectService.deleteChapter(id, projectPath);
  }
}
