package com.codepilot.ai;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DiffParser {

    @org.springframework.beans.factory.annotation.Value("${codepilot.review.max-file-bytes:102400}")
    private int maxFileSize = 100 * 1024;

    @org.springframework.beans.factory.annotation.Value("${codepilot.review.max-diff-bytes:2097152}")
    private int maxDiffSize = 2 * 1024 * 1024;

    public List<ParsedFile> parseDiff(String diffContent) {
        if (diffContent == null || diffContent.isBlank()) {
            return List.of();
        }
        if (diffContent.length() > maxDiffSize) {
            throw new IllegalArgumentException("Pull request diff exceeds the review size limit");
        }

        List<ParsedFile> files = new ArrayList<>();
        ParsedFile current = null;
        int newLine = 0;
        int oldLine = 0;
        boolean sawHunk = false;
        String[] lines = diffContent.split("\\R", -1);

        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                current = finish(current, files);
                current = new ParsedFile();
                current.setDiffContent(new StringBuilder());
                newLine = 0;
                oldLine = 0;
                sawHunk = false;
                String[] paths = line.substring("diff --git ".length()).split(" ");
                if (paths.length >= 2) {
                    current.setOldFilePath(stripPrefix(paths[0], "a/"));
                    current.setFilePath(stripPrefix(paths[1], "b/"));
                }
                continue;
            }
            if (current == null) {
                continue;
            }
            current.getDiffContent().append(line).append('\n');
            if (line.startsWith("GIT binary patch") || line.equals("Binary files differ")) {
                current.setBinary(true);
                continue;
            }
            if (line.startsWith("rename from ")) {
                current.setRenamed(true);
                current.setOldFilePath(line.substring("rename from ".length()));
                continue;
            }
            if (line.startsWith("rename to ")) {
                current.setRenamed(true);
                current.setFilePath(line.substring("rename to ".length()));
                continue;
            }
            if (line.startsWith("deleted file mode")) {
                current.setDeleted(true);
                continue;
            }
            if (line.startsWith("new file mode")) {
                current.setNewFile(true);
                continue;
            }
            if (line.startsWith("--- ")) {
                String path = line.substring(4);
                if (!"/dev/null".equals(path) && !path.equals("a/")) {
                    current.setOldFilePath(stripPrefix(path, "a/"));
                }
                continue;
            }
            if (line.startsWith("+++ ")) {
                String path = line.substring(4);
                if (!"/dev/null".equals(path)) {
                    current.setFilePath(stripPrefix(path, "b/"));
                }
                continue;
            }
            if (line.startsWith("@@")) {
                Hunk hunk = parseHunk(line);
                if (hunk == null) {
                    current.setIncomplete(true);
                    continue;
                }
                oldLine = hunk.oldStart;
                newLine = hunk.newStart;
                current.getHunks().add(line);
                sawHunk = true;
                continue;
            }
            if (line.startsWith("\\ No newline")) {
                // Standard unified diff metadata indicating no trailing newline; not a truncated diff.
                continue;
            }
            if (!sawHunk || line.isEmpty()) {
                continue;
            }
            char marker = line.charAt(0);
            if (marker == ' ') {
                oldLine++;
                newLine++;
                current.getChangedLineRanges().add(new LineRange(oldLine, newLine, false, false));
            } else if (marker == '+') {
                current.getAddedLines().add(newLine);
                current.getChangedLineRanges().add(new LineRange(null, newLine, true, false));
                newLine++;
            } else if (marker == '-') {
                current.getRemovedLines().add(oldLine);
                current.getChangedLineRanges().add(new LineRange(oldLine, null, false, true));
                oldLine++;
            } else {
                current.setIncomplete(true);
            }
        }
        finish(current, files);
        files.forEach(file -> file.setReviewable(isReviewable(file)));
        return files;
    }

    private ParsedFile finish(ParsedFile file, List<ParsedFile> files) {
        if (file != null && file.getFilePath() != null) {
            files.add(file);
        }
        return null;
    }

    private boolean isReviewable(ParsedFile file) {
        if (file.getHunks().isEmpty()) {
            file.setIncomplete(true);
        }
        String path = file.getFilePath() == null ? "" : file.getFilePath().toLowerCase();
        return !file.isBinary()
                && !file.isDeleted()
                && !file.isIncomplete()
                && file.getDiffContent().length() <= maxFileSize
                && !path.endsWith(".lock")
                && !path.endsWith(".png")
                && !path.endsWith(".jpg")
                && !path.endsWith(".jpeg")
                && !path.endsWith(".gif")
                && !path.endsWith(".svg")
                && !path.endsWith(".ico")
                && !path.endsWith(".webp")
                && !path.endsWith(".pdf")
                && !path.endsWith(".zip")
                && !path.endsWith(".tar")
                && !path.endsWith(".gz")
                && !path.endsWith(".jar")
                && !path.endsWith(".class")
                && !path.endsWith(".exe")
                && !path.endsWith(".dll")
                && !path.endsWith(".so")
                && !path.endsWith(".dylib")
                && !path.endsWith(".woff")
                && !path.endsWith(".woff2")
                && !path.endsWith(".ttf")
                && !path.endsWith(".eot")
                && !path.endsWith(".min.js")
                && !path.endsWith(".min.css")
                && !path.endsWith(".map")
                && !path.endsWith(".md")
                && !path.endsWith(".txt")
                && !path.contains("package-lock.json")
                && !path.endsWith("yarn.lock")
                && !path.endsWith("pnpm-lock.yaml");
    }

    private String stripPrefix(String path, String prefix) {
        return path.startsWith(prefix) ? path.substring(prefix.length()) : path;
    }

    private Hunk parseHunk(String line) {
        try {
            String header = line.substring(2, line.indexOf(" @@")).trim();
            String[] ranges = header.split(" ");
            String[] oldRange = ranges[0].substring(1).split(",");
            String[] newRange = ranges[1].substring(1).split(",");
            return new Hunk(Integer.parseInt(oldRange[0]), Integer.parseInt(newRange[0]));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private record Hunk(int oldStart, int newStart) {}

    public record LineRange(Integer oldLine, Integer newLine, boolean added, boolean removed) {}

    @Data
    public static class ParsedFile {
        private String filePath;
        private String oldFilePath;
        private StringBuilder diffContent = new StringBuilder();
        private List<Integer> addedLines = new ArrayList<>();
        private List<Integer> removedLines = new ArrayList<>();
        private List<String> hunks = new ArrayList<>();
        private List<LineRange> changedLineRanges = new ArrayList<>();
        private boolean renamed;
        private boolean newFile;
        private boolean deleted;
        private boolean binary;
        private boolean incomplete;
        private boolean reviewable = true;
    }
}
