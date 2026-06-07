package app.dreamwriter.project;

import app.dreamwriter.project.ProjectModels.ChapterEntry;
import app.dreamwriter.project.ProjectModels.ProjectDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class ProjectFileService {
  private final ObjectMapper objectMapper;

  public ProjectFileService() {
    this.objectMapper = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .enable(SerializationFeature.INDENT_OUTPUT)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  }

  public ProjectDocument createProject(Path basePath, String title, String genre) throws IOException {
    String projectTitle = requireText(title, "项目标题不能为空");
    Path root = basePath.resolve(safeFileName(projectTitle)).toAbsolutePath().normalize();
    ensureProjectRootAvailable(root);
    Files.createDirectories(root);
    createProjectDirectories(root);

    Instant now = Instant.now();
    ProjectDocument project = new ProjectDocument();
    project.id = "novel_" + now.toEpochMilli();
    project.title = projectTitle;
    project.genre = emptyToDefault(genre, "未分类");
    project.description = "";
    project.cover = "";
    project.tags = new ArrayList<>();
    project.genrePrompt = "";
    project.createdAt = now;
    project.updatedAt = now;
    project.lastOpenedAt = now;
    project.settings = new LinkedHashMap<>();
    project.settings.put("outline", "outline.md");
    project.settings.put("styleGuide", "style-guide.md");
    project.settings.put("characters", "settings/characters.md");
    project.settings.put("world", "settings/world.md");
    project.memory = new LinkedHashMap<>();
    project.memory.put("important", "memory/important.md");
    project.memory.put("chapterSummaries", "memory/chapter-summaries.md");
    project.memory.put("plot", "memory/plot-memory.md");

    writeIfAbsent(root.resolve("README.md"), "# " + projectTitle + "\n");
    writeIfAbsent(root.resolve("outline.md"), "# 大纲\n\n");
    writeIfAbsent(root.resolve("style-guide.md"), "# 写作风格\n\n");
    writeIfAbsent(root.resolve("settings/characters.md"), "# 角色设定\n\n");
    writeIfAbsent(root.resolve("settings/world.md"), "# 世界观\n\n");
    writeIfAbsent(root.resolve("settings/timeline.md"), "# 时间线\n\n");
    writeIfAbsent(root.resolve("memory/important.md"), "# 重要记忆\n\n");
    writeIfAbsent(root.resolve("memory/chapter-summaries.md"), "# 章节摘要\n\n");
    writeIfAbsent(root.resolve("memory/plot-memory.md"), "# 剧情记忆\n\n");
    saveProject(root, project);
    return project;
  }

  public ProjectDocument readProject(Path root) throws IOException {
    Path projectFile = root.resolve("project.json");
    if (!Files.exists(projectFile)) {
      throw new IllegalArgumentException("项目目录缺少 project.json: " + root);
    }
    return objectMapper.readValue(projectFile.toFile(), ProjectDocument.class);
  }

  public void saveProject(Path root, ProjectDocument project) throws IOException {
    saveProject(root, project, true);
  }

  public void saveProject(Path root, ProjectDocument project, boolean touchUpdatedAt) throws IOException {
    if (touchUpdatedAt) {
      project.updatedAt = Instant.now();
    }
    objectMapper.writeValue(root.resolve("project.json").toFile(), project);
  }

  public void createProjectDirectories(Path root) throws IOException {
    for (String name : new String[] {
        "chapters", "settings", "memory", "prompts", "generations", "drafts", "exports", "assets",
        ".dream-writer", ".trash"
    }) {
      Files.createDirectories(root.resolve(name));
    }
  }

  public Path resolveInsideProject(Path root, String relativePath) {
    Path resolved = root.resolve(relativePath).normalize();
    if (!resolved.startsWith(root.normalize())) {
      throw new IllegalArgumentException("非法项目路径: " + relativePath);
    }
    return resolved;
  }

  public String readMarkdown(Path path) throws IOException {
    if (!Files.exists(path)) {
      return "";
    }
    String raw = Files.readString(path, StandardCharsets.UTF_8);
    return stripFrontMatter(raw);
  }

  public String normalizeChapterBody(String title, String content) {
    if (content == null || content.isBlank()) {
      return "";
    }
    String normalized = content.stripLeading();
    String headingPrefix = "# " + singleLine(title == null ? "" : title).trim();
    if (headingPrefix.equals("#")) {
      return normalized.strip();
    }
    while (normalized.startsWith(headingPrefix)) {
      normalized = normalized.substring(headingPrefix.length()).stripLeading();
    }
    return normalized.strip();
  }

  public void writeMarkdown(Path path, String content) throws IOException {
    Files.createDirectories(path.getParent());
    Files.writeString(path, content == null ? "" : content, StandardCharsets.UTF_8);
  }

  public void writeChapterMarkdown(Path path, ChapterEntry chapter, String content) throws IOException {
    Files.createDirectories(path.getParent());
    String markdown = """
        ---
        id: %s
        title: %s
        description: %s
        status: %s
        wordCount: %d
        createdAt: %s
        updatedAt: %s
        ---

        # %s

        %s
        """.formatted(
        chapter.id,
        chapter.title,
        singleLine(chapter.description),
        chapter.status,
        chapter.wordCount,
        chapter.createdAt,
        chapter.updatedAt,
        chapter.title,
        normalizeChapterBody(chapter.title, content)
    );
    Files.writeString(path, markdown, StandardCharsets.UTF_8);
  }

  public void moveToTrash(Path root, String relativePath) throws IOException {
    Path source = resolveInsideProject(root, relativePath);
    if (!Files.exists(source)) {
      return;
    }
    Path target = root.resolve(".trash")
        .resolve(Instant.now().toEpochMilli() + "-" + source.getFileName().toString())
        .normalize();
    Files.createDirectories(target.getParent());
    Files.move(source, target);
  }

  public ChapterEntry newChapter(ProjectDocument project, String title, String description, String status) {
    String chapterTitle = emptyToDefault(title, "未命名章节");
    Instant now = Instant.now();
    int nextOrder = project.chapters.stream()
        .map(chapter -> chapter.order)
        .max(Comparator.naturalOrder())
        .orElse(0) + 1;

    ChapterEntry chapter = new ChapterEntry();
    chapter.id = "ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    chapter.title = chapterTitle;
    chapter.order = nextOrder;
    chapter.description = emptyToDefault(description, "");
    chapter.status = emptyToDefault(status, "draft");
    chapter.wordCount = 0;
    chapter.createdAt = now;
    chapter.updatedAt = now;
    chapter.path = "chapters/%03d-%s.md".formatted(nextOrder, safeFileName(chapterTitle));
    return chapter;
  }

  public int countWords(String markdown) {
    if (markdown == null || markdown.isBlank()) {
      return 0;
    }
    String text = markdown
        .replaceAll("(?s)---.*?---", "")
        .replaceAll("<[^>]+>", "")
        .replaceAll("[#>*_`\\-\\[\\]()!]", "")
        .trim();
    return text.replaceAll("\\s+", "").length();
  }

  private String stripFrontMatter(String raw) {
    if (raw.startsWith("---")) {
      int end = raw.indexOf("\n---", 3);
      if (end >= 0) {
        return raw.substring(end + 4).stripLeading();
      }
    }
    return raw;
  }

  private void writeIfAbsent(Path path, String content) throws IOException {
    if (!Files.exists(path)) {
      Files.createDirectories(path.getParent() == null ? path.toAbsolutePath().getParent() : path.getParent());
      Files.writeString(path, content, StandardCharsets.UTF_8);
    }
  }

  private void ensureProjectRootAvailable(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("项目路径已存在且不是目录: " + root);
    }
    try (Stream<Path> entries = Files.list(root)) {
      if (entries.findAny().isPresent()) {
        throw new IllegalArgumentException("项目目录已存在内容，请更换作品名称或存放目录: " + root);
      }
    }
  }

  private String requireText(String value, String message) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(message);
    }
    return value.trim();
  }

  private String emptyToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private String singleLine(String value) {
    return value == null ? "" : value.replaceAll("\\R+", " ").trim();
  }

  private String safeFileName(String value) {
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replaceAll("[\\\\/:*?\"<>|]", "-")
        .replaceAll("\\s+", "-")
        .replaceAll("-+", "-")
        .replaceAll("^-|-$", "");
    return normalized.isBlank() ? "untitled" : normalized;
  }
}
