package app.dreamwriter.project;

import app.dreamwriter.chapter.ChapterModels.ApplyChapterContentRequest;
import app.dreamwriter.chapter.ChapterModels.ApplyChapterContentResult;
import app.dreamwriter.chapter.ChapterModels.ChapterDetail;
import app.dreamwriter.chapter.ChapterModels.ChapterSummary;
import app.dreamwriter.chapter.ChapterModels.UpdateChapterRequest;
import app.dreamwriter.common.PageResponse;
import app.dreamwriter.project.ProjectIndexService.IndexedChapter;
import app.dreamwriter.project.ProjectModels.ChapterEntry;
import app.dreamwriter.project.ProjectModels.CreateProjectBackupRequest;
import app.dreamwriter.project.ProjectModels.ProjectBackupDetail;
import app.dreamwriter.project.ProjectModels.ProjectBackupDiff;
import app.dreamwriter.project.ProjectModels.ProjectBackupDiffItem;
import app.dreamwriter.project.ProjectModels.ProjectBackupItem;
import app.dreamwriter.project.ProjectModels.ProjectBackupSummary;
import app.dreamwriter.project.ProjectModels.ProjectConflict;
import app.dreamwriter.project.ProjectModels.ProjectDocument;
import app.dreamwriter.project.ProjectModels.ProjectFileState;
import app.dreamwriter.project.ProjectModels.ProjectNovelSummary;
import app.dreamwriter.project.ProjectModels.ProjectRegistryProject;
import app.dreamwriter.project.ProjectModels.RestoreProjectBackupRequest;
import app.dreamwriter.project.ProjectModels.ProjectSyncEvent;
import app.dreamwriter.project.ProjectModels.ProjectState;
import app.dreamwriter.project.ProjectModels.ProjectSyncState;
import app.dreamwriter.project.ProjectModels.ProjectSyncStatus;
import app.dreamwriter.project.ProjectModels.ProjectWriterDocument;
import app.dreamwriter.project.ProjectModels.ProjectWriterView;
import app.dreamwriter.project.ProjectModels.UpdateProjectMetadataRequest;
import app.dreamwriter.search.ProjectSearchService;
import app.dreamwriter.search.SearchModels.SearchResult;
import app.dreamwriter.usage.UsageModels.ApiCallRecord;
import app.dreamwriter.usage.UsageModels.ApiCallRecordRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.stereotype.Service;

@Service
public class ProjectService {
  private static final int WATCHED_PATH_LIMIT = 12;
  private static final int MAX_DIFF_FILE_BYTES = 256 * 1024;
  private static final int MAX_EXACT_DIFF_LINES = 900;
  private static final int MAX_DIFF_PREVIEW_LINES = 120;
  private static final DateTimeFormatter BACKUP_ID_FORMATTER =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").withZone(ZoneOffset.UTC);
  private final ProjectFileService fileService;
  private final ProjectIndexService indexService;
  private final ProjectRegistryService registryService;
  private final ProjectSearchService searchService;
  private final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .enable(SerializationFeature.INDENT_OUTPUT)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private Path currentRoot;
  private ProjectDocument currentProject;
  private final Object watchStateLock = new Object();
  private WatchService projectWatchService;
  private Thread projectWatchThread;
  private long watchRevision = 0;
  private Instant changeDetectedAt;
  private boolean externalChangesPending = false;
  private Instant suppressWatchEventsUntil;
  private final List<String> changedPaths = new ArrayList<>();

  public ProjectService(
      ProjectFileService fileService,
      ProjectIndexService indexService,
      ProjectRegistryService registryService,
      ProjectSearchService searchService
  ) {
    this.fileService = fileService;
    this.indexService = indexService;
    this.registryService = registryService;
    this.searchService = searchService;
  }

  public synchronized ProjectState createProject(String basePath, String title, String genre)
      throws IOException, SQLException {
    Path base = Path.of(basePath).toAbsolutePath().normalize();
    fileService.createProject(base, title, genre);
    Path root = base.resolve(safeProjectDirectory(title)).toAbsolutePath().normalize();
    return openProject(root.toString());
  }

  public synchronized ProjectState openProject(String path) throws IOException, SQLException {
    Path root = Path.of(path).toAbsolutePath().normalize();
    fileService.createProjectDirectories(root);
    ProjectDocument project = fileService.readProject(root);
    project.lastOpenedAt = Instant.now();
    fileService.saveProject(root, project, false);
    currentRoot = root;
    currentProject = project;
    restartProjectWatcher(root);
    ProjectState state = rebuildIndex();
    recordProjectInRegistry(root, project, "project");
    indexService.recordSyncEvent(root, "project-opened", state.syncState());
    return state;
  }

  public synchronized ProjectState inspectProject(String path) throws IOException, SQLException {
    Path root = Path.of(path).toAbsolutePath().normalize();
    fileService.createProjectDirectories(root);
    ProjectDocument project = fileService.readProject(root);
    ProjectState state = projectState(root, project);
    indexService.upsertSyncState(root, state.syncState());
    indexService.replaceFileStates(root, scanFileStates(root));
    return state;
  }

  public synchronized List<ProjectState> inspectProjects(List<String> paths) throws IOException, SQLException {
    List<ProjectState> items = new ArrayList<>();
    if (paths == null || paths.isEmpty()) {
      return items;
    }

    for (String path : paths) {
      if (path == null || path.isBlank()) {
        continue;
      }
      try {
        items.add(inspectProject(path));
      } catch (IllegalArgumentException | IOException exception) {
        System.err.println("批量读取项目摘要失败: " + path + " - " + exception.getMessage());
      }
    }
    return items;
  }

  public synchronized List<ProjectNovelSummary> summarizeProjects(List<String> paths)
      throws IOException, SQLException {
    List<ProjectNovelSummary> items = new ArrayList<>();
    if (paths == null || paths.isEmpty()) {
      return items;
    }

    Set<Path> seenRoots = new LinkedHashSet<>();
    for (String path : paths) {
      if (path == null || path.isBlank()) {
        continue;
      }
      Path root = Path.of(path).toAbsolutePath().normalize();
      if (!seenRoots.add(root)) {
        continue;
      }
      try {
        items.add(summarizeProject(root));
      } catch (IllegalArgumentException | IOException exception) {
        System.err.println("读取项目小说摘要失败: " + path + " - " + exception.getMessage());
      }
    }
    return items;
  }

  public synchronized ProjectState currentProject() {
    ensureOpen();
    return projectState(currentRoot, currentProject);
  }

  public synchronized ProjectWriterView writerView(String projectPath) throws IOException, SQLException {
    ProjectState state;
    if (projectPath != null && !projectPath.isBlank()) {
      state = openProject(projectPath);
    } else {
      ensureOpen();
      state = projectState(currentRoot, currentProject);
    }

    ProjectDocument project = state.project();
    Path root = Path.of(state.rootPath()).toAbsolutePath().normalize();
    List<ChapterDetail> chapters = new ArrayList<>();
    for (ChapterEntry chapter : project.chapters == null ? List.<ChapterEntry>of() : project.chapters) {
      chapters.add(toDetail(chapter, ""));
    }
    chapters.sort(Comparator.comparingInt(ChapterDetail::order));

    Map<String, ProjectWriterDocument> documents = new LinkedHashMap<>();
    for (Map.Entry<String, DocumentTarget> entry : documentTargets(project).entrySet()) {
      String content = "";
      try {
        content = fileService.readMarkdown(fileService.resolveInsideProject(root, entry.getValue().path()));
      } catch (IOException ignored) {
        // Missing optional project documents should not block opening the writer.
      }
      documents.put(entry.getKey(), new ProjectWriterDocument(
          entry.getKey(),
          entry.getValue().title(),
          entry.getValue().path(),
          content
      ));
    }

    return new ProjectWriterView(state, chapters, documents);
  }

  public synchronized ProjectState refreshProject() throws IOException, SQLException {
    return refreshProject(null);
  }

  public synchronized ProjectState refreshProject(String projectPath) throws IOException, SQLException {
    ensureProjectOpen(projectPath);
    currentProject = fileService.readProject(currentRoot);
    rebuildIndex(null);
    clearExternalChangeState();
    ProjectState state = projectState(currentRoot, currentProject);
    indexService.upsertSyncState(currentRoot, state.syncState());
    indexService.replaceFileStates(currentRoot, scanFileStates(currentRoot));
    indexService.recordSyncEvent(currentRoot, "project-refreshed", state.syncState());
    return state;
  }

  public synchronized ProjectState updateProjectMetadata(UpdateProjectMetadataRequest request) throws IOException {
    if (request == null) {
      throw new IllegalArgumentException("缺少项目元数据");
    }

    Path root;
    if (request.projectPath() != null && !request.projectPath().isBlank()) {
      root = Path.of(request.projectPath()).toAbsolutePath().normalize();
    } else {
      ensureOpen();
      root = currentRoot;
    }

    boolean isCurrentProject = currentRoot != null && currentRoot.equals(root) && currentProject != null;
    ProjectDocument project = isCurrentProject ? currentProject : fileService.readProject(root);
    boolean touchedProjectSummary = false;

    if (request.title() != null && !request.title().isBlank()) {
      project.title = request.title().trim();
      touchedProjectSummary = true;
    }
    if (request.genre() != null && !request.genre().isBlank()) {
      project.genre = request.genre().trim();
      touchedProjectSummary = true;
    }
    if (request.status() != null && !request.status().isBlank()) {
      project.status = request.status().trim();
      touchedProjectSummary = true;
    }
    if (request.description() != null) {
      project.description = request.description().trim();
      touchedProjectSummary = true;
    }
    if (request.cover() != null) {
      project.cover = request.cover().trim();
      touchedProjectSummary = true;
    }
    if (request.tags() != null) {
      project.tags = request.tags().stream()
          .map(this::normalizeOptionalText)
          .filter(tag -> tag != null && !tag.isBlank())
          .toList();
      touchedProjectSummary = true;
    }
    if (request.genrePrompt() != null) {
      project.genrePrompt = request.genrePrompt().trim();
      touchedProjectSummary = true;
    }

    String legacyNovelId = normalizeOptionalText(request.legacyNovelId());
    project.legacyNovelId = legacyNovelId;
    if (legacyNovelId == null) {
      project.legacyNovelTitle = null;
      project.legacyImportedAt = null;
      project.legacyUpdatedAt = null;
      project.legacyChapterMapping = null;
    } else {
      project.legacyNovelTitle = normalizeOptionalText(request.legacyNovelTitle());
      project.legacyImportedAt = request.legacyImportedAt();
      project.legacyUpdatedAt = request.legacyUpdatedAt();
      project.legacyChapterMapping = normalizeStringMap(request.legacyChapterMapping());
    }

    if (isCurrentProject) {
      noteAppManagedWrite();
    }
    fileService.saveProject(root, project, touchedProjectSummary);

    if (isCurrentProject) {
      currentProject = project;
    }
    recordProjectInRegistry(root, project, "project");
    return projectState(root, project);
  }

  public synchronized ProjectSyncState currentSyncState() throws IOException, SQLException {
    return currentSyncState(null);
  }

  public synchronized ProjectSyncState currentSyncState(String projectPath) throws IOException, SQLException {
    Path root = resolveProjectRoot(projectPath);
    ProjectSyncState syncState = buildSyncState(root);
    indexService.upsertSyncState(root, syncState);
    return syncState;
  }

  public synchronized List<ProjectSyncEvent> listSyncEvents(String projectPath, int limit)
      throws IOException, SQLException {
    Path root;
    if (projectPath != null && !projectPath.isBlank()) {
      root = Path.of(projectPath).toAbsolutePath().normalize();
      fileService.createProjectDirectories(root);
      fileService.readProject(root);
    } else {
      ensureOpen();
      root = currentRoot;
    }
    return indexService.listSyncEvents(root, limit);
  }

  public synchronized ProjectBackupDetail createProjectBackup(CreateProjectBackupRequest request)
      throws IOException, SQLException {
    Path root = resolveProjectRoot(request == null ? null : request.projectPath());
    ProjectDocument project = fileService.readProject(root);
    ProjectSyncStatus status = buildProjectSyncStatus(root, project, true);
    ProjectSyncState syncState = status.projectState().syncState();
    Instant createdAt = Instant.now();
    String backupId = createBackupId(createdAt);
    Path backupRoot = root.resolve(".dream-writer/backups").resolve(backupId).toAbsolutePath().normalize();
    Files.createDirectories(backupRoot.resolve("files"));

    List<ProjectBackupItem> items = new ArrayList<>();
    for (ProjectFileState file : status.files()) {
      ProjectBackupItem item = copyToBackup(root, backupRoot, backupId, file.relativePath(), file.fileType());
      if (item != null) {
        items.add(item);
      }
    }

    ProjectBackupItem dbItem = copyToBackup(root, backupRoot, backupId, ".dream-writer/index.db", "database");
    if (dbItem != null) {
      items.add(dbItem);
    }
    items.addAll(copyLuceneIndexToBackup(root, backupRoot, backupId));

    long totalBytes = items.stream().mapToLong(ProjectBackupItem::size).sum();
    ProjectBackupSummary summary = new ProjectBackupSummary(
        backupId,
        backupLabel(request, createdAt),
        request == null || request.reason() == null ? "" : request.reason().trim(),
        root.toString(),
        backupRoot.toString(),
        project.id,
        project.title,
        syncState == null ? "" : syncState.fingerprint(),
        items.size(),
        totalBytes,
        createdAt
    );
    ProjectBackupDetail detail = new ProjectBackupDetail(summary, items);
    objectMapper.writeValue(backupRoot.resolve("manifest.json").toFile(), new ProjectBackupManifest(summary, items));
    indexService.recordBackup(root, summary, items);
    indexService.recordSyncEvent(root, "backup-created", syncState);
    return detail;
  }

  public synchronized ProjectBackupDetail createChapterSnapshot(String chapterId)
      throws IOException, SQLException {
    ensureOpen();
    ChapterEntry chapter = findChapter(chapterId);
    return createProjectBackup(new CreateProjectBackupRequest(
        currentRoot.toString(),
        "章节快照：" + (chapter.title == null || chapter.title.isBlank() ? chapter.id : chapter.title),
        "chapter-snapshot:" + chapter.id
    ));
  }

  public synchronized List<ProjectBackupSummary> listProjectBackups(String projectPath, int limit)
      throws IOException, SQLException {
    return indexService.listBackups(resolveProjectRoot(projectPath), limit);
  }

  public synchronized ProjectBackupDetail readProjectBackup(String projectPath, String backupId)
      throws IOException, SQLException {
    return requireProjectBackup(resolveProjectRoot(projectPath), backupId);
  }

  public synchronized ProjectBackupDiff compareProjectBackup(String projectPath, String backupId)
      throws IOException, SQLException {
    Path root = resolveProjectRoot(projectPath);
    ProjectBackupDetail backup = requireProjectBackup(root, backupId);
    Path backupRoot = validateBackupRoot(root, backup.summary().backupPath());

    Map<String, ProjectBackupItem> backupItems = new LinkedHashMap<>();
    for (ProjectBackupItem item : backup.items()) {
      backupItems.put(item.relativePath(), item);
    }

    Map<String, ProjectBackupItem> currentItems = currentComparableBackupItems(root);
    Set<String> paths = new TreeSet<>();
    paths.addAll(backupItems.keySet());
    paths.addAll(currentItems.keySet());

    int changedCount = 0;
    int unchangedCount = 0;
    int backupOnlyCount = 0;
    int currentOnlyCount = 0;
    List<ProjectBackupDiffItem> diffItems = new ArrayList<>();

    for (String relativePath : paths) {
      ProjectBackupItem backupItem = backupItems.get(relativePath);
      ProjectBackupItem currentItem = currentItems.get(relativePath);
      String status = backupDiffStatus(backupItem, currentItem);
      if ("unchanged".equals(status)) {
        unchangedCount += 1;
      } else {
        changedCount += 1;
      }
      if ("backup-only".equals(status)) {
        backupOnlyCount += 1;
      } else if ("current-only".equals(status)) {
        currentOnlyCount += 1;
      }

      DiffStats diffStats = buildTextDiffPreview(root, backupRoot, backupItem, currentItem);
      diffItems.add(new ProjectBackupDiffItem(
          relativePath,
          backupItem == null ? currentItem.itemType() : backupItem.itemType(),
          status,
          backupItem == null ? 0L : backupItem.size(),
          currentItem == null ? 0L : currentItem.size(),
          backupItem == null ? "" : backupItem.contentHash(),
          currentItem == null ? "" : currentItem.contentHash(),
          backupItem == null ? null : backupItem.modifiedAt(),
          currentItem == null ? null : currentItem.modifiedAt(),
          diffStats.addedLines(),
          diffStats.removedLines(),
          diffStats.previewLines()
      ));
    }

    return new ProjectBackupDiff(
        backup.summary(),
        changedCount,
        unchangedCount,
        backupOnlyCount,
        currentOnlyCount,
        diffItems
    );
  }

  public synchronized ProjectState restoreProjectBackup(RestoreProjectBackupRequest request)
      throws IOException, SQLException {
    if (request == null || request.backupId() == null || request.backupId().isBlank()) {
      throw new IllegalArgumentException("缺少备份 ID");
    }

    Path root = resolveProjectRoot(request.projectPath());
    ProjectBackupDetail backup = requireProjectBackup(root, request.backupId());
    Path backupRoot = validateBackupRoot(root, backup.summary().backupPath());
    Path backupFilesRoot = backupRoot.resolve("files").normalize();
    if (!Files.isDirectory(backupFilesRoot)) {
      throw new IllegalArgumentException("备份文件目录不存在: " + backupFilesRoot);
    }

    boolean current = isCurrentRoot(root);
    if (current) {
      noteAppManagedWrite();
    }

    Set<String> backupPaths = new HashSet<>();
    boolean restoresSearchIndex = false;
    for (ProjectBackupItem item : backup.items()) {
      backupPaths.add(item.relativePath());
      if ("search-index".equals(item.itemType())) {
        restoresSearchIndex = true;
      }
    }

    deleteProjectFilesMissingFromBackup(root, backupPaths);
    if (restoresSearchIndex) {
      deleteDirectory(root.resolve(".dream-writer/lucene"));
    }

    for (ProjectBackupItem item : backup.items()) {
      copyBackupItemToProject(root, backupRoot, item.relativePath());
    }

    ProjectDocument project = fileService.readProject(root);
    if (current) {
      currentProject = project;
      clearExternalChangeState();
    }
    ProjectState state = projectState(root, project);
    indexService.recordBackup(root, backup.summary(), backup.items());
    indexService.upsertSyncState(root, state.syncState());
    indexService.replaceFileStates(root, scanFileStates(root));
    indexService.recordSyncEvent(root, "backup-restored", state.syncState());
    return state;
  }

  public synchronized ProjectSyncStatus syncProject(String projectPath) throws IOException, SQLException {
    Path root = resolveProjectRoot(projectPath);
    ProjectDocument project = fileService.readProject(root);
    boolean isCurrent = isCurrentRoot(root);
    if (isCurrent) {
      currentProject = project;
      clearExternalChangeState();
    }
    ProjectSyncStatus status = buildProjectSyncStatus(root, project, true);
    indexService.recordSyncEvent(root, "project-synced", status.projectState().syncState());
    return status;
  }

  public synchronized ProjectSyncStatus rescanProject(String projectPath) throws IOException, SQLException {
    Path root = resolveProjectRoot(projectPath);
    ProjectDocument project = fileService.readProject(root);
    boolean isCurrent = isCurrentRoot(root);
    if (isCurrent) {
      currentProject = project;
      clearExternalChangeState();
    }
    rebuildProjectIndexes(root, project);
    ProjectSyncStatus status = buildProjectSyncStatus(root, project, true);
    indexService.recordSyncEvent(root, "project-rescanned", status.projectState().syncState());
    return status;
  }

  public synchronized ProjectSyncStatus projectStatus(String projectPath) throws IOException, SQLException {
    Path root = resolveProjectRoot(projectPath);
    ProjectDocument project = fileService.readProject(root);
    return buildProjectSyncStatus(root, project, true);
  }

  public synchronized List<ProjectConflict> projectConflicts(String projectPath) throws IOException, SQLException {
    return projectStatus(projectPath).conflicts();
  }

  public synchronized PageResponse<ChapterSummary> listChapters(int page, int size, String keyword, String status)
      throws IOException, SQLException {
    return listChapters(page, size, keyword, status, null);
  }

  public synchronized PageResponse<ChapterSummary> listChapters(
      int page,
      int size,
      String keyword,
      String status,
      String projectPath
  ) throws IOException, SQLException {
    ensureProjectOpen(projectPath);
    ProjectIndexService.ChapterPage result = indexService.queryChapters(currentRoot, page, size, keyword, status);
    return new PageResponse<>(
        result.page(),
        result.size(),
        result.total(),
        result.items().stream().map(this::toSummary).toList()
    );
  }

  public synchronized ChapterDetail readChapter(String id) throws IOException, SQLException {
    return readChapter(id, null);
  }

  public synchronized ChapterDetail readChapter(String id, String projectPath) throws IOException, SQLException {
    ensureProjectOpen(projectPath);
    ChapterEntry chapter = findChapter(id);
    String content = fileService.readMarkdown(fileService.resolveInsideProject(currentRoot, chapter.path));
    content = fileService.normalizeChapterBody(chapter.title, content);
    return toDetail(chapter, content);
  }

  public synchronized ChapterDetail createChapter(String title) throws IOException, SQLException {
    return createChapter(null, title, "", "draft");
  }

  public synchronized ChapterDetail createChapter(String projectPath, String title, String description, String status)
      throws IOException, SQLException {
    ensureProjectOpen(projectPath);
    noteAppManagedWrite();
    ChapterEntry chapter = fileService.newChapter(currentProject, title, description, status);
    currentProject.chapters.add(chapter);
    Path chapterPath = fileService.resolveInsideProject(currentRoot, chapter.path);
    fileService.writeChapterMarkdown(chapterPath, chapter, "");
    fileService.saveProject(currentRoot, currentProject);
    indexService.upsertChapter(currentRoot, chapter, "");
    searchService.upsertChapter(currentRoot, chapter, "");
    return toDetail(chapter, "");
  }

  public synchronized ChapterDetail updateChapter(String id, UpdateChapterRequest request)
      throws IOException, SQLException {
    if (request == null) {
      throw new IllegalArgumentException("缺少章节更新内容");
    }
    ensureProjectOpen(request.projectPath());
    noteAppManagedWrite();
    ChapterEntry chapter = findChapter(id);
    String content = request.content();
    if (content == null) {
      content = fileService.readMarkdown(fileService.resolveInsideProject(currentRoot, chapter.path));
    }
    if (request.title() != null && !request.title().isBlank()) {
      chapter.title = request.title().trim();
    }
    if (request.description() != null) {
      chapter.description = request.description().trim();
    }
    if (request.status() != null && !request.status().isBlank()) {
      chapter.status = request.status().trim();
    }
    if (content != null) {
      content = fileService.normalizeChapterBody(chapter.title, content);
    }
    chapter.wordCount = fileService.countWords(content);
    chapter.updatedAt = Instant.now();
    fileService.writeChapterMarkdown(fileService.resolveInsideProject(currentRoot, chapter.path), chapter, content);
    fileService.saveProject(currentRoot, currentProject);
    indexService.upsertChapter(currentRoot, chapter, content);
    searchService.upsertChapter(currentRoot, chapter, content);
    return toDetail(chapter, content);
  }

  public synchronized ApplyChapterContentResult applyChapterContent(
      String id,
      ApplyChapterContentRequest request
  ) throws IOException, SQLException {
    if (request == null) {
      throw new IllegalArgumentException("缺少章节应用内容");
    }
    ensureProjectOpen(request.projectPath());

    ProjectBackupDetail backup = null;
    String backupReason = normalizeOptionalText(request.backupReason());
    if (backupReason != null) {
      ChapterEntry chapter = findChapter(id);
      backup = createProjectBackup(new CreateProjectBackupRequest(
          currentRoot.toString(),
          normalizeOptionalText(request.backupLabel()) == null
              ? "AI 写入前：" + (chapter.title == null || chapter.title.isBlank() ? chapter.id : chapter.title)
              : request.backupLabel().trim(),
          backupReason
      ));
    }

    ChapterDetail detail = updateChapter(id, new UpdateChapterRequest(
        request.projectPath(),
        request.title(),
        request.description(),
        request.status(),
        request.content()
    ));
    return new ApplyChapterContentResult(detail, backup);
  }

  public synchronized void deleteChapter(String id) throws IOException, SQLException {
    deleteChapter(id, null);
  }

  public synchronized void deleteChapter(String id, String projectPath) throws IOException, SQLException {
    ensureProjectOpen(projectPath);
    noteAppManagedWrite();
    ChapterEntry chapter = findChapter(id);
    currentProject.chapters.removeIf(item -> item.id.equals(id));
    fileService.moveToTrash(currentRoot, chapter.path);
    fileService.saveProject(currentRoot, currentProject);
    indexService.deleteChapter(currentRoot, id);
    searchService.deleteChapter(currentRoot, id);
  }

  public synchronized ProjectBackupDetail createChapterSnapshot(String chapterId, String projectPath)
      throws IOException, SQLException {
    ensureProjectOpen(projectPath);
    return createChapterSnapshot(chapterId);
  }

  public synchronized ProjectState rebuildIndex() throws SQLException, IOException {
    return rebuildIndex(null);
  }

  public synchronized ProjectState rebuildIndex(String projectPath) throws SQLException, IOException {
    ensureProjectOpen(projectPath);
    reconcileProjectChapters(currentRoot, currentProject);
    ProjectState state = rebuildProjectIndexes(currentRoot, currentProject);
    indexService.recordSyncEvent(currentRoot, "index-rebuilt", state.syncState());
    return state;
  }

  public synchronized ProjectState reconcileProject(String projectPath) throws SQLException, IOException {
    ensureProjectOpen(projectPath);
    noteAppManagedWrite();
    int removedCount = reconcileProjectChapters(currentRoot, currentProject);
    if (removedCount > 0) {
      fileService.saveProject(currentRoot, currentProject);
    }
    ProjectState state = rebuildProjectIndexes(currentRoot, currentProject);
    indexService.recordSyncEvent(
        currentRoot,
        "chapters-reconciled",
        state.syncState()
    );
    return state;
  }

  private int reconcileProjectChapters(Path root, ProjectDocument project) throws SQLException, IOException {
    List<ChapterEntry> chapters =
        project.chapters == null ? List.of() : project.chapters;
    if (chapters.isEmpty()) {
      return 0;
    }

    List<ChapterEntry> kept = new ArrayList<>();
    int removedCount = 0;
    for (ChapterEntry chapter : chapters) {
      if (chapter.path == null || chapter.path.isBlank()) {
        kept.add(chapter);
        continue;
      }
      Path chapterPath = fileService.resolveInsideProject(root, chapter.path);
      if (Files.exists(chapterPath)) {
        kept.add(chapter);
        continue;
      }
      removedCount += 1;
      indexService.deleteChapter(root, chapter.id);
      searchService.deleteChapter(root, chapter.id);
    }

    if (removedCount == 0) {
      return 0;
    }

    project.chapters = kept;
    for (int index = 0; index < kept.size(); index += 1) {
      kept.get(index).order = index + 1;
    }
    return removedCount;
  }

  private ProjectState rebuildProjectIndexes(Path root, ProjectDocument project) throws SQLException, IOException {
    indexService.initialize(root);
    searchService.initialize(root);
    indexService.clearChapters(root);
    searchService.clear(root);
    for (ChapterEntry chapter : project.chapters == null ? List.<ChapterEntry>of() : project.chapters) {
      String content = "";
      try {
        content = fileService.readMarkdown(fileService.resolveInsideProject(root, chapter.path));
      } catch (IOException ignored) {
        // Keep metadata searchable even if the chapter file has not been created yet.
      }
      indexService.upsertChapter(root, chapter, content);
      searchService.upsertChapter(root, chapter, content);
    }

    for (ProjectFile projectFile : readProjectFilesForIndexing(root, project)) {
      searchService.upsertDocument(
          root,
          projectFile.key(),
          projectFile.title(),
          projectFile.path(),
          documentScope(projectFile.key()),
          project.updatedAt,
          projectFile.content()
      );
    }
    ProjectState state = projectState(root, project);
    indexService.upsertSyncState(root, state.syncState());
    indexService.replaceFileStates(root, scanFileStates(root));
    return state;
  }

  private ProjectState projectState(Path root, ProjectDocument project) {
    Path dbPath = root.resolve(".dream-writer/index.db").toAbsolutePath().normalize();
    ProjectSyncState syncState =
        currentRoot != null && currentRoot.equals(root) ? buildSyncState(root) : computeSyncState(root);
    return new ProjectState(root.toString(), dbPath.toString(), project, syncState);
  }

  private ProjectNovelSummary summarizeProject(Path root) throws IOException, SQLException {
    fileService.createProjectDirectories(root);
    ProjectDocument project = fileService.readProject(root);
    ProjectSyncStatus status = buildProjectSyncStatus(root, project, true);
    ProjectState state = status.projectState();
    ProjectSyncState syncState = state.syncState();
    List<ProjectFileState> files = status.files() == null ? List.of() : status.files();
    ProjectFileState latestFile = latestTrackedFile(files);
    int wordCount = 0;
    List<ChapterEntry> chapters = project.chapters == null ? List.of() : project.chapters;
    for (ChapterEntry chapter : chapters) {
      wordCount += Math.max(0, chapter.wordCount);
    }

    return new ProjectNovelSummary(
        state.rootPath(),
        state.indexDbPath(),
        project.id,
        project.title,
        project.genre,
        project.status,
        project.description,
        project.cover,
        project.tags == null ? List.of() : project.tags,
        project.genrePrompt,
        chapters,
        chapters.size(),
        wordCount,
        project.createdAt,
        project.updatedAt,
        project.lastOpenedAt,
        project.legacyNovelId,
        project.legacyNovelTitle,
        project.legacyImportedAt,
        project.legacyUpdatedAt,
        project.legacyChapterMapping,
        summarySyncStatus(status, syncState),
        syncState.scannedAt(),
        syncState.watchRevision(),
        syncState.externalChangesPending(),
        syncState.changedPaths(),
        syncState.fingerprint(),
        status.trackedFileCount(),
        status.conflictCount(),
        latestFile == null ? "" : latestFile.relativePath()
    );
  }

  private void recordProjectInRegistry(Path root, ProjectDocument project, String source) {
    try {
      registryService.register(new ProjectRegistryProject(
          root.toString(),
          project == null || project.title == null || project.title.isBlank()
              ? root.getFileName().toString()
              : project.title,
          source == null || source.isBlank() ? "project" : source,
          null,
          project == null || project.lastOpenedAt == null ? Instant.now() : project.lastOpenedAt
      ));
    } catch (IOException | IllegalArgumentException error) {
      System.err.println("记录项目注册表失败: " + root + " - " + error.getMessage());
    }
  }

  private String summarySyncStatus(ProjectSyncStatus status, ProjectSyncState syncState) {
    if (status != null && status.conflictCount() > 0) {
      return "attention-required";
    }
    if (syncState != null && syncState.externalChangesPending()) {
      return "external-changes";
    }
    return "synced";
  }

  private ProjectFileState latestTrackedFile(List<ProjectFileState> files) {
    ProjectFileState latest = null;
    for (ProjectFileState file : files == null ? List.<ProjectFileState>of() : files) {
      if (latest == null) {
        latest = file;
        continue;
      }
      Instant fileModifiedAt = file.modifiedAt();
      Instant latestModifiedAt = latest.modifiedAt();
      if (fileModifiedAt != null && (latestModifiedAt == null || fileModifiedAt.isAfter(latestModifiedAt))) {
        latest = file;
      }
    }
    return latest;
  }

  private ProjectSyncStatus buildProjectSyncStatus(Path root, ProjectDocument project, boolean persist)
      throws IOException, SQLException {
    ProjectState state = projectState(root, project);
    List<ProjectFileState> files = scanFileStates(root);
    List<ProjectConflict> conflicts = detectProjectConflicts(root, project, files);
    if (persist) {
      indexService.upsertSyncState(root, state.syncState());
      indexService.replaceFileStates(root, files);
    }
    String syncStatus = conflicts.isEmpty() ? "synced" : "attention-required";
    return new ProjectSyncStatus(
        state,
        syncStatus,
        files.size(),
        conflicts.size(),
        files,
        conflicts
    );
  }

  private List<ProjectFileState> scanFileStates(Path root) throws IOException {
    Instant scannedAt = Instant.now();
    try (Stream<Path> stream = Files.walk(root)) {
      List<Path> files = stream
          .filter(Files::isRegularFile)
          .filter(file -> isTrackedProjectFile(root, file))
          .sorted()
          .toList();
      List<ProjectFileState> items = new ArrayList<>();
      for (Path file : files) {
        String relativePath = root.relativize(file).toString().replace('\\', '/');
        FileTime modifiedTime = Files.getLastModifiedTime(file);
        items.add(new ProjectFileState(
            relativePath,
            projectFileType(relativePath),
            contentHash(file),
            Files.size(file),
            modifiedTime.toInstant(),
            scannedAt,
            "tracked"
        ));
      }
      return items;
    }
  }

  private List<ProjectConflict> detectProjectConflicts(
      Path root,
      ProjectDocument project,
      List<ProjectFileState> files
  ) {
    Instant detectedAt = Instant.now();
    Set<String> existingPaths = new HashSet<>();
    for (ProjectFileState file : files) {
      existingPaths.add(file.relativePath());
    }

    List<ProjectConflict> conflicts = new ArrayList<>();
    for (String expectedPath : expectedProjectPaths(project)) {
      if (!existingPaths.contains(expectedPath)) {
        conflicts.add(new ProjectConflict(
            expectedPath,
            projectFileType(expectedPath),
            "missing-tracked-file",
            detectedAt
        ));
      }
    }

    if (isCurrentRoot(root)) {
      synchronized (watchStateLock) {
        if (externalChangesPending) {
          for (String changedPath : changedPaths) {
            conflicts.add(new ProjectConflict(
                changedPath,
                projectFileType(changedPath),
                "external-change-pending",
                changeDetectedAt == null ? detectedAt : changeDetectedAt
            ));
          }
        }
      }
    }
    return conflicts;
  }

  private Set<String> expectedProjectPaths(ProjectDocument project) {
    Set<String> paths = new LinkedHashSet<>();
    paths.add("project.json");
    for (DocumentTarget target : documentTargets(project).values()) {
      paths.add(target.path());
    }
    if (project.chapters != null) {
      for (ChapterEntry chapter : project.chapters) {
        if (chapter.path != null && !chapter.path.isBlank()) {
          paths.add(chapter.path);
        }
      }
    }
    return paths;
  }

  private String projectFileType(String relativePath) {
    if (relativePath == null || relativePath.isBlank()) {
      return "unknown";
    }
    if (relativePath.equals("project.json")) {
      return "project";
    }
    if (relativePath.startsWith("chapters/")) {
      return "chapter";
    }
    if (relativePath.startsWith("settings/")) {
      return "setting";
    }
    if (relativePath.startsWith("memory/")) {
      return "memory";
    }
    return "document";
  }

  private String contentHash(Path file) throws IOException {
    MessageDigest digest = createDigest();
    digest.update(Files.readAllBytes(file));
    return hex(digest.digest());
  }

  private String createBackupId(Instant createdAt) {
    return "backup-"
        + BACKUP_ID_FORMATTER.format(createdAt)
        + "-"
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  private String backupLabel(CreateProjectBackupRequest request, Instant createdAt) {
    String label = request == null ? null : normalizeOptionalText(request.label());
    return label == null ? "项目快照 " + createdAt : label;
  }

  private ProjectBackupItem copyToBackup(
      Path root,
      Path backupRoot,
      String backupId,
      String relativePath,
      String itemType
  ) throws IOException {
    Path source = fileService.resolveInsideProject(root, relativePath);
    if (!Files.isRegularFile(source)) {
      return null;
    }

    Path target = resolveBackupStoragePath(backupRoot, relativePath);
    Files.createDirectories(target.getParent());
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
    FileTime modifiedTime = Files.getLastModifiedTime(source);
    return new ProjectBackupItem(
        backupId,
        relativePath,
        itemType == null || itemType.isBlank() ? "file" : itemType,
        Files.size(source),
        contentHash(source),
        modifiedTime.toInstant()
    );
  }

  private List<ProjectBackupItem> copyLuceneIndexToBackup(Path root, Path backupRoot, String backupId)
      throws IOException {
    Path luceneRoot = root.resolve(".dream-writer/lucene").toAbsolutePath().normalize();
    if (!Files.isDirectory(luceneRoot)) {
      return List.of();
    }

    List<ProjectBackupItem> items = new ArrayList<>();
    try (Stream<Path> stream = Files.walk(luceneRoot)) {
      for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
        String relativePath = root.relativize(file).toString().replace('\\', '/');
        ProjectBackupItem item = copyToBackup(root, backupRoot, backupId, relativePath, "search-index");
        if (item != null) {
          items.add(item);
        }
      }
    }
    return items;
  }

  private Map<String, ProjectBackupItem> currentComparableBackupItems(Path root) throws IOException {
    Map<String, ProjectBackupItem> items = new LinkedHashMap<>();
    for (ProjectFileState file : scanFileStates(root)) {
      items.put(file.relativePath(), new ProjectBackupItem(
          "",
          file.relativePath(),
          file.fileType(),
          file.size(),
          file.contentHash(),
          file.modifiedAt()
      ));
    }

    addCurrentComparableItem(root, items, ".dream-writer/index.db", "database");
    Path luceneRoot = root.resolve(".dream-writer/lucene").toAbsolutePath().normalize();
    if (Files.isDirectory(luceneRoot)) {
      try (Stream<Path> stream = Files.walk(luceneRoot)) {
        for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
          String relativePath = root.relativize(file).toString().replace('\\', '/');
          addCurrentComparableItem(root, items, relativePath, "search-index");
        }
      }
    }
    return items;
  }

  private void addCurrentComparableItem(
      Path root,
      Map<String, ProjectBackupItem> items,
      String relativePath,
      String itemType
  ) throws IOException {
    Path file = fileService.resolveInsideProject(root, relativePath);
    if (!Files.isRegularFile(file)) {
      return;
    }
    items.put(relativePath, new ProjectBackupItem(
        "",
        relativePath,
        itemType,
        Files.size(file),
        contentHash(file),
        Files.getLastModifiedTime(file).toInstant()
    ));
  }

  private String backupDiffStatus(ProjectBackupItem backupItem, ProjectBackupItem currentItem) {
    if (backupItem == null) {
      return "current-only";
    }
    if (currentItem == null) {
      return "backup-only";
    }
    String backupHash = backupItem.contentHash() == null ? "" : backupItem.contentHash();
    String currentHash = currentItem.contentHash() == null ? "" : currentItem.contentHash();
    return backupHash.equals(currentHash) ? "unchanged" : "modified";
  }

  private DiffStats buildTextDiffPreview(
      Path root,
      Path backupRoot,
      ProjectBackupItem backupItem,
      ProjectBackupItem currentItem
  ) throws IOException {
    ProjectBackupItem reference = backupItem == null ? currentItem : backupItem;
    if (reference == null || !supportsTextDiff(reference)) {
      return DiffStats.empty();
    }

    String relativePath = reference.relativePath();
    if (tooLargeForDiff(backupItem) || tooLargeForDiff(currentItem)) {
      return DiffStats.empty();
    }

    String backupContent = "";
    if (backupItem != null) {
      Path backupFile = resolveBackupStoragePath(backupRoot, relativePath);
      if (Files.isRegularFile(backupFile)) {
        backupContent = Files.readString(backupFile, StandardCharsets.UTF_8);
      }
    }

    String currentContent = "";
    if (currentItem != null) {
      Path currentFile = fileService.resolveInsideProject(root, relativePath);
      if (Files.isRegularFile(currentFile)) {
        currentContent = Files.readString(currentFile, StandardCharsets.UTF_8);
      }
    }

    return diffForRestore(currentContent, backupContent);
  }

  private boolean supportsTextDiff(ProjectBackupItem item) {
    String type = item.itemType() == null ? "" : item.itemType();
    if ("database".equals(type) || "search-index".equals(type)) {
      return false;
    }
    String relativePath = item.relativePath() == null ? "" : item.relativePath().toLowerCase();
    return relativePath.endsWith(".md")
        || relativePath.endsWith(".json")
        || relativePath.endsWith(".txt")
        || relativePath.endsWith(".yaml")
        || relativePath.endsWith(".yml");
  }

  private boolean tooLargeForDiff(ProjectBackupItem item) {
    return item != null && item.size() > MAX_DIFF_FILE_BYTES;
  }

  private DiffStats diffForRestore(String currentContent, String backupContent) {
    List<String> currentLines = splitLines(currentContent);
    List<String> backupLines = splitLines(backupContent);
    if (currentLines.size() > MAX_EXACT_DIFF_LINES || backupLines.size() > MAX_EXACT_DIFF_LINES) {
      return simpleDiffForRestore(currentLines, backupLines);
    }

    int[][] lcs = new int[currentLines.size() + 1][backupLines.size() + 1];
    for (int i = currentLines.size() - 1; i >= 0; i--) {
      for (int j = backupLines.size() - 1; j >= 0; j--) {
        if (currentLines.get(i).equals(backupLines.get(j))) {
          lcs[i][j] = lcs[i + 1][j + 1] + 1;
        } else {
          lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        }
      }
    }

    int currentIndex = 0;
    int backupIndex = 0;
    int added = 0;
    int removed = 0;
    List<String> preview = new ArrayList<>();
    while (currentIndex < currentLines.size() || backupIndex < backupLines.size()) {
      if (
          currentIndex < currentLines.size()
              && backupIndex < backupLines.size()
              && currentLines.get(currentIndex).equals(backupLines.get(backupIndex))
      ) {
        currentIndex += 1;
        backupIndex += 1;
      } else if (
          backupIndex < backupLines.size()
              && (currentIndex == currentLines.size()
                  || lcs[currentIndex][backupIndex + 1] >= lcs[currentIndex + 1][backupIndex])
      ) {
        added += 1;
        appendPreviewLine(preview, "+ ", backupLines.get(backupIndex));
        backupIndex += 1;
      } else if (currentIndex < currentLines.size()) {
        removed += 1;
        appendPreviewLine(preview, "- ", currentLines.get(currentIndex));
        currentIndex += 1;
      }
    }
    return new DiffStats(added, removed, preview);
  }

  private DiffStats simpleDiffForRestore(List<String> currentLines, List<String> backupLines) {
    int added = 0;
    int removed = 0;
    List<String> preview = new ArrayList<>();
    int max = Math.max(currentLines.size(), backupLines.size());
    for (int index = 0; index < max; index++) {
      String currentLine = index < currentLines.size() ? currentLines.get(index) : null;
      String backupLine = index < backupLines.size() ? backupLines.get(index) : null;
      if (currentLine != null && currentLine.equals(backupLine)) {
        continue;
      }
      if (currentLine != null) {
        removed += 1;
        appendPreviewLine(preview, "- ", currentLine);
      }
      if (backupLine != null) {
        added += 1;
        appendPreviewLine(preview, "+ ", backupLine);
      }
    }
    return new DiffStats(added, removed, preview);
  }

  private List<String> splitLines(String content) {
    if (content == null || content.isEmpty()) {
      return List.of();
    }
    return List.of(content.split("\\R", -1));
  }

  private void appendPreviewLine(List<String> preview, String prefix, String line) {
    if (preview.size() >= MAX_DIFF_PREVIEW_LINES) {
      return;
    }
    String value = line == null ? "" : line;
    if (value.length() > 240) {
      value = value.substring(0, 240) + "...";
    }
    preview.add(prefix + value);
  }

  private void copyBackupItemToProject(Path root, Path backupRoot, String relativePath) throws IOException {
    Path source = resolveBackupStoragePath(backupRoot, relativePath);
    if (!Files.isRegularFile(source)) {
      throw new IllegalArgumentException("备份条目文件不存在: " + relativePath);
    }

    Path target = fileService.resolveInsideProject(root, relativePath);
    Files.createDirectories(target.getParent());
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
  }

  private Path resolveBackupStoragePath(Path backupRoot, String relativePath) {
    Path filesRoot = backupRoot.resolve("files").normalize();
    Path resolved = filesRoot.resolve(relativePath).normalize();
    if (!resolved.startsWith(filesRoot)) {
      throw new IllegalArgumentException("非法备份条目路径: " + relativePath);
    }
    return resolved;
  }

  private void deleteProjectFilesMissingFromBackup(Path root, Set<String> backupPaths) throws IOException {
    for (ProjectFileState file : scanFileStates(root)) {
      if (backupPaths.contains(file.relativePath())) {
        continue;
      }
      Files.deleteIfExists(fileService.resolveInsideProject(root, file.relativePath()));
    }
  }

  private void deleteDirectory(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    try (Stream<Path> stream = Files.walk(directory)) {
      for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private ProjectBackupDetail requireProjectBackup(Path root, String backupId) throws SQLException {
    String normalizedBackupId = normalizeOptionalText(backupId);
    if (normalizedBackupId == null) {
      throw new IllegalArgumentException("缺少备份 ID");
    }
    ProjectBackupDetail backup = indexService.readBackup(root, normalizedBackupId);
    if (backup == null) {
      throw new IllegalArgumentException("备份不存在: " + normalizedBackupId);
    }
    return backup;
  }

  private Path validateBackupRoot(Path root, String backupPath) {
    Path backupsRoot = root.resolve(".dream-writer/backups").toAbsolutePath().normalize();
    Path backupRoot = Path.of(backupPath).toAbsolutePath().normalize();
    if (!backupRoot.startsWith(backupsRoot)) {
      throw new IllegalArgumentException("备份路径不在当前项目目录内: " + backupPath);
    }
    return backupRoot;
  }

  private Path resolveProjectRoot(String projectPath) throws IOException {
    if (projectPath == null || projectPath.isBlank()) {
      ensureOpen();
      return currentRoot;
    }
    Path root = Path.of(projectPath).toAbsolutePath().normalize();
    fileService.createProjectDirectories(root);
    fileService.readProject(root);
    return root;
  }

  private boolean isCurrentRoot(Path root) {
    return currentRoot != null && currentRoot.equals(root);
  }

  private ProjectSyncState buildSyncState(Path root) {
    ProjectSyncState baseState = computeSyncState(root);
    synchronized (watchStateLock) {
      return new ProjectSyncState(
          baseState.fingerprint(),
          baseState.latestFileModifiedAt(),
          baseState.scannedAt(),
          watchRevision,
          changeDetectedAt,
          externalChangesPending,
          List.copyOf(changedPaths)
      );
    }
  }

  private ProjectSyncState computeSyncState(Path root) {
    Instant scannedAt = Instant.now();
    Instant latestModifiedAt = null;
    MessageDigest digest = createDigest();

    try (Stream<Path> stream = Files.walk(root)) {
      for (Path path : stream.filter(Files::isRegularFile).filter(file -> isTrackedProjectFile(root, file)).sorted().toList()) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        FileTime modifiedTime = Files.getLastModifiedTime(path);
        long size = Files.size(path);
        Instant modifiedAt = modifiedTime.toInstant();
        if (latestModifiedAt == null || modifiedAt.isAfter(latestModifiedAt)) {
          latestModifiedAt = modifiedAt;
        }
        digest.update(relative.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        digest.update(Long.toString(size).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        digest.update(Long.toString(modifiedTime.toMillis()).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
        digest.update(contentHash(path).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
      }
    } catch (IOException exception) {
      throw new IllegalStateException("读取项目同步状态失败: " + root, exception);
    }

    return new ProjectSyncState(
        hex(digest.digest()),
        latestModifiedAt,
        scannedAt,
        0L,
        null,
        false,
        List.of()
    );
  }

  private boolean isTrackedProjectFile(Path root, Path file) {
    String relative = root.relativize(file).toString().replace('\\', '/');
    return relative.equals("project.json")
        || relative.equals("outline.md")
        || relative.equals("style-guide.md")
        || relative.startsWith("chapters/")
        || relative.startsWith("settings/")
        || relative.startsWith("memory/");
  }

  private MessageDigest createDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前运行环境缺少 SHA-256", exception);
    }
  }

  private String hex(byte[] bytes) {
    StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      builder.append(String.format("%02x", value));
    }
    return builder.toString();
  }

  public synchronized ProjectFile readProjectFile(String key) throws IOException {
    try {
      return readProjectFile(key, null);
    } catch (SQLException exception) {
      throw new IOException("读取项目文件失败", exception);
    }
  }

  public synchronized ProjectFile readProjectFile(String key, String projectPath) throws IOException, SQLException {
    ensureProjectOpen(projectPath);
    DocumentTarget target = documentTarget(key);
    String content = fileService.readMarkdown(fileService.resolveInsideProject(currentRoot, target.path()));
    return new ProjectFile(key, target.title(), target.path(), content);
  }

  public synchronized ProjectFile updateProjectFile(String key, String content) throws IOException {
    try {
      return updateProjectFile(key, content, null);
    } catch (SQLException exception) {
      throw new IOException("更新项目文件失败", exception);
    }
  }

  public synchronized ProjectFile updateProjectFile(String key, String content, String projectPath)
      throws IOException, SQLException {
    ensureProjectOpen(projectPath);
    noteAppManagedWrite();
    DocumentTarget target = documentTarget(key);
    String value = content == null ? "" : content;
    fileService.writeMarkdown(fileService.resolveInsideProject(currentRoot, target.path()), value);
    fileService.saveProject(currentRoot, currentProject);
    ProjectFile projectFile = new ProjectFile(key, target.title(), target.path(), value);
    searchService.upsertDocument(
        currentRoot,
        key,
        target.title(),
        target.path(),
        documentScope(key),
        currentProject.updatedAt,
        value
    );
    return projectFile;
  }

  private void restartProjectWatcher(Path root) throws IOException {
    stopProjectWatcher();
    synchronized (watchStateLock) {
      watchRevision = 0;
      changeDetectedAt = null;
      externalChangesPending = false;
      suppressWatchEventsUntil = null;
      changedPaths.clear();
    }

    projectWatchService = root.getFileSystem().newWatchService();
    registerWatchDirectory(root);
    registerWatchDirectory(root.resolve("chapters"));
    registerWatchDirectory(root.resolve("settings"));
    registerWatchDirectory(root.resolve("memory"));

    projectWatchThread = new Thread(() -> watchProjectLoop(root), "dream-writer-project-watch");
    projectWatchThread.setDaemon(true);
    projectWatchThread.start();
  }

  private void registerWatchDirectory(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      return;
    }
    directory.register(
        projectWatchService,
        StandardWatchEventKinds.ENTRY_CREATE,
        StandardWatchEventKinds.ENTRY_MODIFY,
        StandardWatchEventKinds.ENTRY_DELETE
    );
  }

  private void stopProjectWatcher() {
    if (projectWatchThread != null) {
      projectWatchThread.interrupt();
      projectWatchThread = null;
    }
    if (projectWatchService != null) {
      try {
        projectWatchService.close();
      } catch (IOException ignored) {
        // Ignore watcher close failures during project switch/shutdown.
      }
      projectWatchService = null;
    }
  }

  private void watchProjectLoop(Path root) {
    while (true) {
      WatchKey key;
      try {
        key = projectWatchService.take();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        return;
      } catch (ClosedWatchServiceException exception) {
        return;
      }

      Path watchedDirectory = (Path) key.watchable();
      for (WatchEvent<?> event : key.pollEvents()) {
        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
          continue;
        }
        Object context = event.context();
        if (!(context instanceof Path changedName)) {
          continue;
        }
        Path changedPath = watchedDirectory.resolve(changedName).normalize();
        if (!changedPath.startsWith(root) || Files.isDirectory(changedPath)) {
          continue;
        }
        String relativePath = root.relativize(changedPath).toString().replace('\\', '/');
        if (!isTrackedRelativePath(relativePath)) {
          continue;
        }
        recordExternalChange(root, relativePath);
      }

      if (!key.reset()) {
        return;
      }
    }
  }

  private boolean isTrackedRelativePath(String relativePath) {
    return relativePath.equals("project.json")
        || relativePath.equals("outline.md")
        || relativePath.equals("style-guide.md")
        || relativePath.startsWith("chapters/")
        || relativePath.startsWith("settings/")
        || relativePath.startsWith("memory/");
  }

  private void noteAppManagedWrite() {
    synchronized (watchStateLock) {
      suppressWatchEventsUntil = Instant.now().plusSeconds(3);
      changeDetectedAt = null;
      externalChangesPending = false;
      changedPaths.clear();
    }
  }

  private void clearExternalChangeState() {
    synchronized (watchStateLock) {
      changeDetectedAt = null;
      externalChangesPending = false;
      changedPaths.clear();
    }
  }

  private void recordExternalChange(Path root, String relativePath) {
    synchronized (watchStateLock) {
      Instant now = Instant.now();
      if (suppressWatchEventsUntil != null && !now.isAfter(suppressWatchEventsUntil)) {
        return;
      }
      watchRevision += 1;
      changeDetectedAt = now;
      externalChangesPending = true;
      changedPaths.remove(relativePath);
      changedPaths.add(relativePath);
      while (changedPaths.size() > WATCHED_PATH_LIMIT) {
        changedPaths.remove(0);
      }
    }
    try {
      indexService.recordSyncEvent(root, "external-change-detected", watchEventSnapshot());
    } catch (SQLException error) {
      System.err.println("记录项目外部变更事件失败: " + error.getMessage());
    }
  }

  public synchronized PageResponse<SearchResult> search(String query, String type, int page, int size)
      throws IOException, SQLException {
    return search(query, type, page, size, null);
  }

  public synchronized PageResponse<SearchResult> search(
      String query,
      String type,
      int page,
      int size,
      String projectPath
  ) throws IOException, SQLException {
    ensureProjectOpen(projectPath);
    return searchService.search(currentRoot, query, type, page, size);
  }

  public synchronized void recordApiCall(ApiCallRecordRequest request) throws SQLException {
    if (request == null || request.projectPath() == null || request.projectPath().isBlank()) {
      throw new IllegalArgumentException("缺少项目路径");
    }
    if (request.id() == null || request.id().isBlank()) {
      throw new IllegalArgumentException("缺少调用记录ID");
    }
    if (request.timestamp() == null || request.timestamp().isBlank()) {
      throw new IllegalArgumentException("缺少调用时间");
    }
    if (request.type() == null || request.type().isBlank()) {
      throw new IllegalArgumentException("缺少调用类型");
    }
    if (request.model() == null || request.model().isBlank()) {
      throw new IllegalArgumentException("缺少模型名称");
    }
    if (request.status() == null || request.status().isBlank()) {
      throw new IllegalArgumentException("缺少调用状态");
    }

    Path root = Path.of(request.projectPath()).toAbsolutePath().normalize();
    indexService.recordApiCall(root, request);
  }

  public synchronized List<ApiCallRecord> listApiCallRecords(String projectPath) throws SQLException {
    if (projectPath == null || projectPath.isBlank()) {
      throw new IllegalArgumentException("缺少项目路径");
    }
    Path root = Path.of(projectPath).toAbsolutePath().normalize();
    return indexService.listApiCallRecords(root);
  }

  private ChapterEntry findChapter(String id) {
    return currentProject.chapters.stream()
        .filter(chapter -> chapter.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("章节不存在: " + id));
  }

  private ChapterSummary toSummary(IndexedChapter chapter) {
    return new ChapterSummary(
        chapter.id(),
        chapter.title(),
        chapter.order(),
        chapter.description(),
        chapter.status(),
        chapter.path(),
        chapter.wordCount(),
        chapter.updatedAt()
    );
  }

  private ChapterDetail toDetail(ChapterEntry chapter, String content) {
    return new ChapterDetail(
        chapter.id,
        chapter.title,
        chapter.order,
        chapter.description,
        chapter.status,
        chapter.path,
        chapter.wordCount,
        chapter.createdAt,
        chapter.updatedAt,
        content
    );
  }

  private void ensureOpen() {
    if (currentRoot == null || currentProject == null) {
      throw new IllegalStateException("请先创建或打开项目");
    }
  }

  private ProjectState ensureProjectOpen(String projectPath) throws IOException, SQLException {
    if (projectPath == null || projectPath.isBlank()) {
      ensureOpen();
      return projectState(currentRoot, currentProject);
    }

    Path root = Path.of(projectPath).toAbsolutePath().normalize();
    if (currentRoot != null && currentRoot.equals(root) && currentProject != null) {
      return projectState(currentRoot, currentProject);
    }
    return openProject(root.toString());
  }

  private String normalizeOptionalText(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private ProjectSyncState watchEventSnapshot() {
    synchronized (watchStateLock) {
      return new ProjectSyncState(
          null,
          null,
          Instant.now(),
          watchRevision,
          changeDetectedAt,
          externalChangesPending,
          List.copyOf(changedPaths)
      );
    }
  }

  private Map<String, String> normalizeStringMap(Map<String, String> value) {
    if (value == null || value.isEmpty()) {
      return null;
    }
    Map<String, String> normalized = new LinkedHashMap<>();
    value.forEach((key, item) -> {
      String normalizedKey = normalizeOptionalText(key);
      String normalizedValue = normalizeOptionalText(item);
      if (normalizedKey != null && normalizedValue != null) {
        normalized.put(normalizedKey, normalizedValue);
      }
    });
    return normalized.isEmpty() ? null : normalized;
  }

  private DocumentTarget documentTarget(String key) {
    DocumentTarget target = documentTargets(currentProject).get(key);
    if (target == null) {
      throw new IllegalArgumentException("未知项目文件: " + key);
    }
    return target;
  }

  private Map<String, DocumentTarget> documentTargets(ProjectDocument project) {
    Map<String, DocumentTarget> targets = new LinkedHashMap<>();
    targets.put("outline", new DocumentTarget("大纲", valueOrDefault(project.settings, "outline", "outline.md")));
    targets.put("styleGuide", new DocumentTarget("写作风格", valueOrDefault(project.settings, "styleGuide", "style-guide.md")));
    targets.put("characters", new DocumentTarget("角色设定", valueOrDefault(project.settings, "characters", "settings/characters.md")));
    targets.put("world", new DocumentTarget("世界观", valueOrDefault(project.settings, "world", "settings/world.md")));
    targets.put("timeline", new DocumentTarget("时间线", "settings/timeline.md"));
    targets.put("important", new DocumentTarget("重要记忆", valueOrDefault(project.memory, "important", "memory/important.md")));
    targets.put("chapterSummaries", new DocumentTarget("章节摘要", valueOrDefault(project.memory, "chapterSummaries", "memory/chapter-summaries.md")));
    targets.put("plot", new DocumentTarget("剧情记忆", valueOrDefault(project.memory, "plot", "memory/plot-memory.md")));
    return targets;
  }

  private List<ProjectFile> readProjectFilesForIndexing(Path root, ProjectDocument project) {
    List<ProjectFile> files = new ArrayList<>();
    for (Map.Entry<String, DocumentTarget> entry : documentTargets(project).entrySet()) {
      String content = "";
      try {
        content = fileService.readMarkdown(fileService.resolveInsideProject(root, entry.getValue().path()));
      } catch (IOException ignored) {
        // Keep project search available even if a target file is still missing.
      }
      files.add(new ProjectFile(entry.getKey(), entry.getValue().title(), entry.getValue().path(), content));
    }
    return files;
  }

  private String documentScope(String key) {
    return switch (key) {
      case "important", "chapterSummaries", "plot" -> "memory";
      default -> "setting";
    };
  }

  private String valueOrDefault(Map<String, String> source, String key, String fallback) {
    if (source == null) {
      return fallback;
    }
    String value = source.get(key);
    return value == null || value.isBlank() ? fallback : value;
  }

  public record ProjectFile(String key, String title, String path, String content) {
  }

  private record ProjectBackupManifest(ProjectBackupSummary summary, List<ProjectBackupItem> items) {
  }

  private record DiffStats(int addedLines, int removedLines, List<String> previewLines) {
    private static DiffStats empty() {
      return new DiffStats(0, 0, List.of());
    }
  }

  private record DocumentTarget(String title, String path) {
  }

  private String safeProjectDirectory(String title) {
    return title == null || title.isBlank()
        ? "dream-writer-project"
        : title.trim().replaceAll("[\\\\/:*?\"<>|]", "-").replaceAll("\\s+", "-");
  }
}
