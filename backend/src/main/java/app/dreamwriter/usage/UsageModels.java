package app.dreamwriter.usage;

import java.util.List;

public final class UsageModels {
  private UsageModels() {
  }

  public record ApiCallRecordRequest(
      String projectPath,
      String id,
      String timestamp,
      String type,
      String model,
      String content,
      String response,
      Integer inputTokens,
      Integer outputTokens,
      Integer totalTokens,
      Double cost,
      String status,
      String novelId,
      String novelTitle
  ) {
  }

  public record ApiCallRecord(
      String id,
      String timestamp,
      String type,
      String model,
      String content,
      String response,
      int inputTokens,
      int outputTokens,
      int totalTokens,
      double cost,
      String status,
      String novelId,
      String novelTitle
  ) {
  }

  public record ApiCallRecordList(
      List<ApiCallRecord> items
  ) {
  }
}
