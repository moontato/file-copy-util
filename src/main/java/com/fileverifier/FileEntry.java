package com.fileverifier;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A file discovered under a scan root, identified by its normalized relative path.
 *
 * <p>{@code relPath} is always relative to the directory that was scanned and always uses
 * '/' as separator. It is the single identity used across the app: list models, selection
 * sets passed to {@link FileSyncEngine}, and the rsync {@code --files-from} list.
 * Absolute paths are carried along only to avoid re-resolving them later.
 */
public record FileEntry(String relPath, Path absolutePath) {

    public FileEntry {
        Objects.requireNonNull(relPath, "relPath");
        Objects.requireNonNull(absolutePath, "absolutePath");
        if (relPath.isEmpty()) {
            throw new IllegalArgumentException("relPath must not be empty");
        }
        if (relPath.startsWith("/")) {
            throw new IllegalArgumentException("relPath must be relative to the scan root: " + relPath);
        }
    }

    @Override
    public String toString() {
        return relPath;
    }
}
