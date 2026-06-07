package app.dreamwriter.search;

import app.dreamwriter.common.PageResponse;
import app.dreamwriter.project.ProjectService;
import app.dreamwriter.search.SearchModels.SearchResult;
import java.io.IOException;
import java.sql.SQLException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
public class SearchController {
  private final ProjectService projectService;

  public SearchController(ProjectService projectService) {
    this.projectService = projectService;
  }

  @GetMapping
  public PageResponse<SearchResult> search(
      @RequestParam(name = "q") String query,
      @RequestParam(defaultValue = "all") String type,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String projectPath
  ) throws IOException, SQLException {
    return projectService.search(query, type, page, size, projectPath);
  }

  @GetMapping("/chapters")
  public PageResponse<SearchResult> searchChapters(
      @RequestParam(name = "q") String query,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String projectPath
  ) throws IOException, SQLException {
    return projectService.search(query, "chapter", page, size, projectPath);
  }

  @GetMapping("/memory")
  public PageResponse<SearchResult> searchMemory(
      @RequestParam(name = "q") String query,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String projectPath
  ) throws IOException, SQLException {
    return projectService.search(query, "memory", page, size, projectPath);
  }
}
