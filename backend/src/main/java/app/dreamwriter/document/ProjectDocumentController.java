package app.dreamwriter.document;

import app.dreamwriter.document.DocumentModels.UpdateDocumentRequest;
import app.dreamwriter.project.ProjectService;
import app.dreamwriter.project.ProjectService.ProjectFile;
import java.io.IOException;
import java.sql.SQLException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
public class ProjectDocumentController {
  private final ProjectService projectService;

  public ProjectDocumentController(ProjectService projectService) {
    this.projectService = projectService;
  }

  @GetMapping("/{key}")
  public ProjectFile read(
      @PathVariable String key,
      @RequestParam(required = false) String projectPath
  ) throws IOException, SQLException {
    return projectService.readProjectFile(key, projectPath);
  }

  @PutMapping("/{key}")
  public ProjectFile update(@PathVariable String key, @RequestBody UpdateDocumentRequest request)
      throws IOException, SQLException {
    return projectService.updateProjectFile(key, request.content(), request.projectPath());
  }
}
