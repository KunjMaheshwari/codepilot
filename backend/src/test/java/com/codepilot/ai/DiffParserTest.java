package com.codepilot.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiffParserTest {

    private final DiffParser parser = new DiffParser();

    @Test
    void parsesAddedRemovedLinesAndRenames() {
        String diff = """
                diff --git a/old.java b/new.java
                similarity index 95%
                rename from old.java
                rename to new.java
                --- a/old.java
                +++ b/new.java
                @@ -2,2 +2,3 @@
                 unchanged
                -removed
                +added
                """;

        DiffParser.ParsedFile file = parser.parseDiff(diff).get(0);

        assertThat(file.getFilePath()).isEqualTo("new.java");
        assertThat(file.getOldFilePath()).isEqualTo("old.java");
        assertThat(file.isRenamed()).isTrue();
        assertThat(file.getAddedLines()).containsExactly(3);
        assertThat(file.getRemovedLines()).containsExactly(3);
        assertThat(file.isReviewable()).isTrue();
    }

    @Test
    void marksBinaryAndIncompleteFilesAsNotReviewable() {
        String diff = """
                diff --git a/image.png b/image.png
                new file mode 100644
                GIT binary patch
                """;

        List<DiffParser.ParsedFile> files = parser.parseDiff(diff);

        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.isBinary()).isTrue();
            assertThat(file.isReviewable()).isFalse();
        });
    }

    @Test
    void rejectsOversizedDiffs() {
        assertThatThrownBy(() -> parser.parseDiff("x".repeat(2 * 1024 * 1024 + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Pull request diff exceeds the review size limit");
    }
}
