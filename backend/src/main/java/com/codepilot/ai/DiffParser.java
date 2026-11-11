package com.codepilot.ai;

import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class DiffParser {

    private static final int MAX_FILE_SIZE = 100 * 1024; // 100 KB rough limit

    public List<ParsedFile> parseDiff(String diffContent) {
        List<ParsedFile> parsedFiles = new ArrayList<>();
        if (!StringUtils.hasText(diffContent)) {
            return parsedFiles;
        }

        String[] lines = diffContent.split("\n");
        ParsedFile currentFile = null;
        StringBuilder currentContent = new StringBuilder();
        int currentLineNumber = 0;

        for (String line : lines) {
            if (line.startsWith("diff --git")) {
                if (currentFile != null) {
                    currentFile.setDiffContent(currentContent.toString());
                    if (isSafeToReview(currentFile)) {
                        parsedFiles.add(currentFile);
                    }
                }
                currentFile = new ParsedFile();
                currentContent = new StringBuilder();
            } else if (line.startsWith("+++ b/")) {
                if (currentFile != null) {
                    currentFile.setFilePath(line.substring(6));
                }
            } else if (line.startsWith("@@ ")) {
                try {
                    String[] parts = line.split(" ");
                    if (parts.length >= 3 && parts[2].startsWith("+")) {
                        String[] lineInfo = parts[2].substring(1).split(",");
                        currentLineNumber = Integer.parseInt(lineInfo[0]) - 1;
                    }
                } catch (Exception ignored) {}
                currentContent.append(line).append("\n");
            } else if (currentFile != null) {
                if (!line.startsWith("-") && !line.startsWith("\\ No newline")) {
                    currentLineNumber++;
                    if (line.startsWith("+")) {
                        currentFile.getAddedLines().add(currentLineNumber);
                    }
                }
                currentContent.append(line).append("\n");
            }
        }

        if (currentFile != null) {
            currentFile.setDiffContent(currentContent.toString());
            if (isSafeToReview(currentFile)) {
                parsedFiles.add(currentFile);
            }
        }

        return parsedFiles;
    }

    private boolean isSafeToReview(ParsedFile file) {
        if (file.getFilePath() == null) return false;
        String lowerPath = file.getFilePath().toLowerCase();

        if (lowerPath.endsWith(".lock") || lowerPath.endsWith(".png") ||
            lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jar") ||
            lowerPath.endsWith(".class") || lowerPath.contains("package-lock.json")) {
            return false;
        }

        return file.getDiffContent().length() <= MAX_FILE_SIZE;
    }

    @Data
    public static class ParsedFile {
        private String filePath;
        private String diffContent;
        private List<Integer> addedLines = new ArrayList<>();
    }
}
