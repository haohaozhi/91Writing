package app.dreamwriter.usage;

import app.dreamwriter.project.ProjectService;
import app.dreamwriter.usage.UsageModels.ApiCallRecord;
import app.dreamwriter.usage.UsageModels.ApiCallRecordRequest;
import java.sql.SQLException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usage")
public class UsageController {
  private final ProjectService projectService;

  public UsageController(ProjectService projectService) {
    this.projectService = projectService;
  }

  @PostMapping("/records")
  public void record(@RequestBody ApiCallRecordRequest request) throws SQLException {
    projectService.recordApiCall(request);
  }

  @GetMapping("/records")
  public List<ApiCallRecord> list(@RequestParam String projectPath) throws SQLException {
    return projectService.listApiCallRecords(projectPath);
  }
}
