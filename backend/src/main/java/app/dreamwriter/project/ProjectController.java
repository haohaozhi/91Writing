package app.dreamwriter.project;

import app.dreamwriter.project.ProjectModels.CreateProjectRequest;
import app.dreamwriter.project.ProjectModels.CreateProjectBackupRequest;
import app.dreamwriter.project.ProjectModels.OpenProjectRequest;
import app.dreamwriter.project.ProjectModels.ProjectBackupDetail;
import app.dreamwriter.project.ProjectModels.ProjectBackupDiff;
import app.dreamwriter.project.ProjectModels.ProjectBackupSummary;
import app.dreamwriter.project.ProjectModels.ProjectPathsRequest;
import app.dreamwriter.project.ProjectModels.ProjectConflict;
import app.dreamwriter.project.ProjectModels.ProjectNovelSummary;
import app.dreamwriter.project.ProjectModels.ProjectRegistryProject;
import app.dreamwriter.project.ProjectModels.ProjectRegistrySnapshot;
import app.dreamwriter.project.ProjectModels.ProjectState;
import app.dreamwriter.project.ProjectModels.ProjectSyncEvent;
import app.dreamwriter.project.ProjectModels.ProjectSyncState;
import app.dreamwriter.project.ProjectModels.ProjectSyncStatus;
import app.dreamwriter.project.ProjectModels.ProjectWriterView;
import app.dreamwriter.project.ProjectModels.RestoreProjectBackupRequest;
import app.dreamwriter.project.ProjectModels.UpdateProjectMetadataRequest;
import java.io.IOException;
import java.util.List;
import java.sql.SQLException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {
  private final ProjectService projectService;
  private final ProjectRegistryService registryService;

  public ProjectController(ProjectService projectService, ProjectRegistryService registryService) {
    this.projectService = projectService;
    this.registryService = registryService;
  }

  @PostMapping("/create")
  public ProjectState create(@RequestBody CreateProjectRequest request) throws IOException, SQLException {
    return projectService.createProject(request.basePath(), request.title(), request.genre());
  }

  @PostMapping("/open")
  public ProjectState open(@RequestBody OpenProjectRequest request) throws IOException, SQLException {
    return projectService.openProject(request.path());
  }

  @PostMapping("/inspect")
  public ProjectState inspect(@RequestBody OpenProjectRequest request) throws IOException, SQLException {
    return projectService.inspectProject(request.path());
  }

  @PostMapping("/inspect-batch")
  public List<ProjectState> inspectBatch(@RequestBody ProjectPathsRequest request)
      throws IOException, SQLException {
    return projectService.inspectProjects(request == null ? List.of() : request.paths());
  }

  @PostMapping("/novel-summaries")
  public List<ProjectNovelSummary> novelSummaries(@RequestBody ProjectPathsRequest request)
      throws IOException, SQLException {
    return projectService.summarizeProjects(request == null ? List.of() : request.paths());
  }

  @GetMapping("/registry")
  public ProjectRegistrySnapshot registry() throws IOException {
    return registryService.snapshot();
  }

  @PostMapping("/registry/replace")
  public ProjectRegistrySnapshot replaceRegistry(@RequestBody(required = false) ProjectRegistrySnapshot request)
      throws IOException {
    return registryService.replace(request);
  }

  @PostMapping("/registry/merge")
  public ProjectRegistrySnapshot mergeRegistry(@RequestBody(required = false) ProjectRegistrySnapshot request)
      throws IOException {
    return registryService.merge(request);
  }

  @PostMapping("/registry/register")
  public ProjectRegistryProject registerProject(@RequestBody ProjectRegistryProject request) throws IOException {
    return registryService.register(request);
  }

  @PostMapping("/registry/remove")
  public ProjectRegistrySnapshot removeProjectFromRegistry(@RequestBody OpenProjectRequest request)
      throws IOException {
    return registryService.remove(request == null ? "" : request.path());
  }

  @PostMapping("/registry/clear")
  public ProjectRegistrySnapshot clearRegistry() throws IOException {
    return registryService.clear();
  }

  @GetMapping("/registry/novel-summaries")
  public List<ProjectNovelSummary> registeredNovelSummaries() throws IOException, SQLException {
    return projectService.summarizeProjects(registryService.listProjectRootPaths());
  }

  @GetMapping("/current")
  public ProjectState current() {
    return projectService.currentProject();
  }

  @PostMapping("/writer-view")
  public ProjectWriterView writerView(@RequestBody(required = false) OpenProjectRequest request)
      throws IOException, SQLException {
    return projectService.writerView(request == null ? null : request.path());
  }

  @PostMapping("/metadata")
  public ProjectState updateMetadata(@RequestBody UpdateProjectMetadataRequest request) throws IOException {
    return projectService.updateProjectMetadata(request);
  }

  @PostMapping("/backups")
  public ProjectBackupDetail createBackup(@RequestBody(required = false) CreateProjectBackupRequest request)
      throws IOException, SQLException {
    return projectService.createProjectBackup(request);
  }

  @GetMapping("/backups")
  public List<ProjectBackupSummary> backups(
      @RequestParam(required = false) String projectPath,
      @RequestParam(defaultValue = "50") int limit
  ) throws IOException, SQLException {
    return projectService.listProjectBackups(projectPath, limit);
  }

  @GetMapping("/backups/{backupId}")
  public ProjectBackupDetail backup(
      @PathVariable String backupId,
      @RequestParam(required = false) String projectPath
  ) throws IOException, SQLException {
    return projectService.readProjectBackup(projectPath, backupId);
  }

  @GetMapping("/backups/{backupId}/diff")
  public ProjectBackupDiff backupDiff(
      @PathVariable String backupId,
      @RequestParam(required = false) String projectPath
  ) throws IOException, SQLException {
    return projectService.compareProjectBackup(projectPath, backupId);
  }

  @PostMapping("/backups/restore")
  public ProjectState restoreBackup(@RequestBody RestoreProjectBackupRequest request)
      throws IOException, SQLException {
    return projectService.restoreProjectBackup(request);
  }

  @PostMapping("/refresh")
  public ProjectState refresh(@RequestBody(required = false) OpenProjectRequest request)
      throws IOException, SQLException {
    return projectService.refreshProject(request == null ? null : request.path());
  }

  @PostMapping("/sync")
  public ProjectSyncStatus sync(@RequestBody(required = false) OpenProjectRequest request)
      throws IOException, SQLException {
    return projectService.syncProject(request == null ? null : request.path());
  }

  @PostMapping("/rescan")
  public ProjectSyncStatus rescan(@RequestBody(required = false) OpenProjectRequest request)
      throws IOException, SQLException {
    return projectService.rescanProject(request == null ? null : request.path());
  }

  @PostMapping("/rebuild-index")
  public ProjectState rebuildIndex(@RequestBody(required = false) OpenProjectRequest request)
      throws SQLException, IOException {
    return projectService.rebuildIndex(request == null ? null : request.path());
  }

  @PostMapping("/reconcile")
  public ProjectState reconcile(@RequestBody(required = false) OpenProjectRequest request)
      throws SQLException, IOException {
    return projectService.reconcileProject(request == null ? null : request.path());
  }

  @GetMapping("/status")
  public ProjectSyncStatus status(@RequestParam(required = false) String projectPath)
      throws IOException, SQLException {
    return projectService.projectStatus(projectPath);
  }

  @GetMapping("/conflicts")
  public List<ProjectConflict> conflicts(@RequestParam(required = false) String projectPath)
      throws IOException, SQLException {
    return projectService.projectConflicts(projectPath);
  }

  @GetMapping("/sync-state")
  public ProjectSyncState syncState(@RequestParam(required = false) String projectPath)
      throws IOException, SQLException {
    return projectService.currentSyncState(projectPath);
  }

  @GetMapping("/sync-events")
  public List<ProjectSyncEvent> syncEvents(
      @RequestParam(required = false) String projectPath,
      @RequestParam(defaultValue = "20") int limit
  ) throws IOException, SQLException {
    return projectService.listSyncEvents(projectPath, limit);
  }
}
