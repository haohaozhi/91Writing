package app.dreamwriter.document;

public final class DocumentModels {
  private DocumentModels() {
  }

  public record UpdateDocumentRequest(String projectPath, String content) {
  }
}
