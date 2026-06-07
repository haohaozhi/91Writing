package app.dreamwriter.search;

import java.time.Instant;

public final class SearchModels {
  private SearchModels() {
  }

  public record SearchResult(
      String type,
      String scope,
      String id,
      String title,
      String path,
      String snippet,
      double score,
      Instant updatedAt
  ) {
  }
}
