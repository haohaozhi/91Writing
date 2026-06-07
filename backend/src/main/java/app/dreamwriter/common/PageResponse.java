package app.dreamwriter.common;

import java.util.List;

public record PageResponse<T>(
    int page,
    int size,
    long total,
    List<T> items
) {
}
