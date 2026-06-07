package app.dreamwriter.project;

import app.dreamwriter.project.ProjectModels.ProjectRegistryProject;
import app.dreamwriter.project.ProjectModels.ProjectRegistrySnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ProjectRegistryService {
  private final ObjectMapper objectMapper = new ObjectMapper()
      .registerModule(new JavaTimeModule())
      .enable(SerializationFeature.INDENT_OUTPUT)
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  public synchronized ProjectRegistrySnapshot snapshot() throws IOException {
    return readSnapshot();
  }

  public synchronized ProjectRegistrySnapshot replace(ProjectRegistrySnapshot snapshot) throws IOException {
    ProjectRegistrySnapshot normalized = normalizeSnapshot(snapshot);
    writeSnapshot(normalized);
    return normalized;
  }

  public synchronized ProjectRegistrySnapshot merge(ProjectRegistrySnapshot snapshot) throws IOException {
    ProjectRegistrySnapshot current = readSnapshot();
    ProjectRegistrySnapshot incoming = normalizeSnapshot(snapshot);
    Map<String, ProjectRegistryProject> projects = new LinkedHashMap<>(current.projects());
    projects.putAll(incoming.projects());
    Map<String, Object> legacyBindings = new LinkedHashMap<>(current.legacyBindings());
    legacyBindings.putAll(incoming.legacyBindings());
    ProjectRegistrySnapshot merged = normalizeSnapshot(new ProjectRegistrySnapshot(projects, legacyBindings));
    writeSnapshot(merged);
    return merged;
  }

  public synchronized ProjectRegistryProject register(ProjectRegistryProject project) throws IOException {
    ProjectRegistrySnapshot current = readSnapshot();
    ProjectRegistryProject normalized = normalizeProject(
        project,
        current.projects().get(normalizeRootPath(project == null ? null : project.rootPath()))
    );
    Map<String, ProjectRegistryProject> projects = new LinkedHashMap<>(current.projects());
    projects.put(normalized.rootPath(), normalized);
    ProjectRegistrySnapshot next = new ProjectRegistrySnapshot(projects, current.legacyBindings());
    writeSnapshot(next);
    return normalized;
  }

  public synchronized ProjectRegistrySnapshot remove(String rootPath) throws IOException {
    String normalizedRootPath = normalizeRootPath(rootPath);
    ProjectRegistrySnapshot current = readSnapshot();
    Map<String, ProjectRegistryProject> projects = new LinkedHashMap<>(current.projects());
    projects.remove(normalizedRootPath);
    ProjectRegistrySnapshot next = new ProjectRegistrySnapshot(projects, current.legacyBindings());
    writeSnapshot(next);
    return next;
  }

  public synchronized ProjectRegistrySnapshot clear() throws IOException {
    ProjectRegistrySnapshot empty = emptySnapshot();
    writeSnapshot(empty);
    return empty;
  }

  public synchronized List<String> listProjectRootPaths() throws IOException {
    return new ArrayList<>(readSnapshot().projects().keySet());
  }

  private ProjectRegistrySnapshot readSnapshot() throws IOException {
    Path path = registryPath();
    if (!Files.exists(path)) {
      return emptySnapshot();
    }
    ProjectRegistrySnapshot snapshot = objectMapper.readValue(path.toFile(), ProjectRegistrySnapshot.class);
    return normalizeSnapshot(snapshot);
  }

  private void writeSnapshot(ProjectRegistrySnapshot snapshot) throws IOException {
    Path path = registryPath();
    Files.createDirectories(path.getParent());
    Path temp = path.resolveSibling(path.getFileName() + ".tmp");
    objectMapper.writeValue(temp.toFile(), normalizeSnapshot(snapshot));
    try {
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private ProjectRegistrySnapshot normalizeSnapshot(ProjectRegistrySnapshot snapshot) {
    if (snapshot == null) {
      return emptySnapshot();
    }
    Map<String, ProjectRegistryProject> projects = new LinkedHashMap<>();
    Map<String, ProjectRegistryProject> sourceProjects =
        snapshot.projects() == null ? Map.of() : snapshot.projects();
    sourceProjects.values().forEach((project) -> {
      if (project == null) {
        return;
      }
      ProjectRegistryProject normalized = normalizeProject(project, null);
      projects.put(normalized.rootPath(), normalized);
    });
    Map<String, Object> legacyBindings = new LinkedHashMap<>();
    if (snapshot.legacyBindings() != null) {
      snapshot.legacyBindings().forEach((key, value) -> {
        String normalizedKey = key == null ? "" : key.trim();
        if (!normalizedKey.isBlank() && value != null) {
          legacyBindings.put(normalizedKey, value);
        }
      });
    }
    return new ProjectRegistrySnapshot(projects, legacyBindings);
  }

  private ProjectRegistryProject normalizeProject(
      ProjectRegistryProject project,
      ProjectRegistryProject current
  ) {
    String rootPath = normalizeRootPath(project == null ? null : project.rootPath());
    if (rootPath.isBlank()) {
      throw new IllegalArgumentException("缺少项目路径");
    }
    Instant now = Instant.now();
    String title = normalizeText(project == null ? null : project.title());
    String source = normalizeText(project == null ? null : project.source());
    return new ProjectRegistryProject(
        rootPath,
        title.isBlank() ? rootPath : title,
        source.isBlank() ? sourceOrDefault(current) : source,
        project == null || project.registeredAt() == null
            ? current == null ? now : current.registeredAt()
            : project.registeredAt(),
        project == null || project.lastOpenedAt() == null ? now : project.lastOpenedAt()
    );
  }

  private ProjectRegistrySnapshot emptySnapshot() {
    return new ProjectRegistrySnapshot(new LinkedHashMap<>(), new LinkedHashMap<>());
  }

  private String sourceOrDefault(ProjectRegistryProject current) {
    String source = current == null ? "" : normalizeText(current.source());
    return source.isBlank() ? "project" : source;
  }

  private String normalizeRootPath(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return Path.of(value).toAbsolutePath().normalize().toString();
  }

  private String normalizeText(String value) {
    return value == null ? "" : value.trim();
  }

  private Path registryPath() {
    String explicitPath = System.getenv("DREAM_WRITER_PROJECT_REGISTRY_PATH");
    if (explicitPath != null && !explicitPath.isBlank()) {
      return Path.of(explicitPath).toAbsolutePath().normalize();
    }
    String userDataPath = System.getenv("DREAM_WRITER_USER_DATA_PATH");
    if (userDataPath != null && !userDataPath.isBlank()) {
      return Path.of(userDataPath).resolve("project-registry.json").toAbsolutePath().normalize();
    }
    return Path.of(System.getProperty("user.home"), ".dream-writer", "project-registry.json")
        .toAbsolutePath()
        .normalize();
  }
}
