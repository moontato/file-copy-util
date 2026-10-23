package com.fileverifier;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * How a directory is walked. Created in Phase 3: the scanner used to hard-code its ignore list,
 * always include hidden files, and never follow a symlink.
 *
 * <p>Immutable value object. {@link #ignorePatterns()} is the complete rule set - supplying a list
 * replaces the defaults rather than adding to them - which is what the frame's "Ignore" field shows
 * and edits.
 */
public record ScanOptions(List<String> ignorePatterns, boolean includeHidden, SymlinkPolicy symlinkPolicy) {

    /** What to do with symbolic links found during a scan. */
    public enum SymlinkPolicy {
        /** Ignore them (default): a symlink is never copied and never verified. */
        SKIP,
        /** Report the link under its own path; rsync keeps it a link, hashing follows the target. */
        LINK,
        /** Report symlinked files by their target content and descend into symlinked directories. */
        FOLLOW
    }

    /** The default ignore list, and the value the frame pre-fills its Ignore field with. */
    public static final List<String> DEFAULT_IGNORED_DIRS =
            List.of(".git", ".svn", ".hg", ".bzr", ".jj", "lost+found", ".Trash", ".DS_Store");

    public static final ScanOptions DEFAULTS =
            new ScanOptions(DEFAULT_IGNORED_DIRS, true, SymlinkPolicy.SKIP);

    public ScanOptions {
        ignorePatterns = List.copyOf(ignorePatterns == null ? List.of() : ignorePatterns);
    }

    public ScanOptions withSymlinkPolicy(SymlinkPolicy policy) {
        return new ScanOptions(ignorePatterns, includeHidden, policy);
    }

    public ScanOptions withIncludeHidden(boolean value) {
        return new ScanOptions(ignorePatterns, value, symlinkPolicy);
    }

    /**
     * One ignore rule. A pattern with no glob metacharacter is an exact name (the common case, and
     * checked without compiling anything); anything else is matched as a glob.
     */
    public record IgnoreRule(String pattern, PathMatcher matcher, boolean glob) {

        static IgnoreRule compile(String pattern) {
            boolean glob = pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0 || pattern.indexOf('[') >= 0;
            PathMatcher matcher = glob ? FileSystems.getDefault().getPathMatcher("glob:" + pattern) : null;
            return new IgnoreRule(pattern, matcher, glob);
        }

        boolean matches(Path relativePath) {
            if (!glob) {
                Path name = relativePath.getFileName();
                return pattern.equals(relativePath.toString())
                        || (name != null && pattern.equals(name.toString()));
            }
            Path name = relativePath.getFileName();
            return matcher.matches(relativePath)
                    || (name != null && matcher.matches(name));
        }
    }

    /**
     * The rules a scan can apply, and the patterns it was asked for that it could not compile.
     *
     * <p>An ignore pattern is a protection: the only reason to write one is to keep something out of the job.
     * A pattern with bad glob syntax cannot protect anything, and dropping it in silence turns a typo into a
     * directory being copied when the reader believed it would be left alone. So the pattern that was not
     * applied is named, by both frontends, in a warning - and nothing else changes: the file is handled
     * normally and judged on what it is, because a broken rule is not evidence about any file.
     */
    public record CompiledRules(List<IgnoreRule> rules, List<String> notApplied) {
        public CompiledRules {
            rules = List.copyOf(rules);
            notApplied = List.copyOf(notApplied);
        }
    }

    /** Compile every pattern once per scan, keeping the ones that did not compile as a named list. */
    public CompiledRules compile() {
        List<IgnoreRule> rules = new ArrayList<>();
        List<String> notApplied = new ArrayList<>();
        for (String pattern : ignorePatterns) {
            try {
                rules.add(IgnoreRule.compile(pattern));
            } catch (IllegalArgumentException e) {
                notApplied.add(pattern);
            }
        }
        return new CompiledRules(rules, notApplied);
    }

    /** The rules alone, for a caller that has nowhere to report the patterns it had to drop. */
    public List<IgnoreRule> compileRules() {
        return compile().rules();
    }

    /** Compilers kept for the scanner: one per scan, reused for every entry. */
    List<PathMatcher> compileMatchers() {
        List<PathMatcher> matchers = new ArrayList<>();
        for (IgnoreRule rule : compileRules()) {
            if (rule.matcher() != null) {
                matchers.add(rule.matcher());
            }
        }
        return matchers;
    }

    /** True when a relative path (file) or a directory path should be ignored. */
    public boolean ignores(String relPath, List<IgnoreRule> rules) {
        if (rules.isEmpty()) {
            return false;
        }
        Path relative = Path.of(relPath);
        for (IgnoreRule rule : rules) {
            if (rule.matches(relative)) {
                return true;
            }
        }
        return false;
    }

    /** True when a file or directory name starts with a dot. */
    static boolean isHiddenName(String name) {
        return name.startsWith(".");
    }

    /** Parses the frame's comma-separated Ignore field into patterns. */
    public static ScanOptions parse(String ignoreCsv, boolean includeHidden, SymlinkPolicy policy) {
        List<String> patterns = new ArrayList<>();
        if (ignoreCsv != null) {
            for (String part : ignoreCsv.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    patterns.add(trimmed);
                }
            }
        }
        return new ScanOptions(patterns, includeHidden, policy);
    }
}
