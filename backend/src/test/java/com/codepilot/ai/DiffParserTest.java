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

    @Test
    void parsesValidJavaDiffEvenWithoutTrailingNewline() {
        String diff = """
                diff --git a/0014-longest-common-prefix/0014-longest-common-prefix.java b/0014-longest-common-prefix/0014-longest-common-prefix.java
                index 3c01def..ba87551 100644
                --- a/0014-longest-common-prefix/0014-longest-common-prefix.java
                +++ b/0014-longest-common-prefix/0014-longest-common-prefix.java
                @@ -1,3 +1,4 @@
                +import java.util.Arrays;
                 class Solution {
                 }
                \\ No newline at end of file
                """;

        List<DiffParser.ParsedFile> files = parser.parseDiff(diff);

        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.isIncomplete()).isFalse();
            assertThat(file.isReviewable()).isTrue();
            assertThat(file.getAddedLines()).containsExactly(1);
        });
    }

    @Test
    void parsesValidJavaScriptAndTypeScriptDiffs() {
        String diff = """
                diff --git a/src/utils/format.ts b/src/utils/format.ts
                index 1111111..2222222 100644
                --- a/src/utils/format.ts
                +++ b/src/utils/format.ts
                @@ -1,2 +1,3 @@
                 export function formatName(name: string): string {
                +  if (!name) return "";
                   return name.trim();
                 }
                diff --git a/src/legacy/helper.js b/src/legacy/helper.js
                index 3333333..4444444 100644
                --- a/src/legacy/helper.js
                +++ b/src/legacy/helper.js
                @@ -1,2 +1,3 @@
                 export const sum = (a, b) => {
                +  console.debug(a, b);
                   return a + b;
                 };
                """;

        List<DiffParser.ParsedFile> files = parser.parseDiff(diff);

        assertThat(files).hasSize(2);
        assertThat(files).allSatisfy(file -> assertThat(file.isReviewable()).isTrue());
    }

    @Test
    void parsesMultipleChangedJavaFilesWithoutCarryingHunkStateAcrossFiles() {
        String diff = """
                diff --git a/SolutionA.java b/SolutionA.java
                index 1111111..2222222 100644
                --- a/SolutionA.java
                +++ b/SolutionA.java
                @@ -1,2 +1,3 @@
                 class SolutionA {
                +  int x = 1;
                 }
                \\ No newline at end of file
                diff --git a/SolutionB.java b/SolutionB.java
                index 3333333..4444444 100644
                --- a/SolutionB.java
                +++ b/SolutionB.java
                @@ -1,2 +1,3 @@
                 class SolutionB {
                +  int y = 2;
                 }
                \\ No newline at end of file
                """;

        List<DiffParser.ParsedFile> files = parser.parseDiff(diff);

        assertThat(files).hasSize(2);
        assertThat(files.get(0).isReviewable()).isTrue();
        assertThat(files.get(1).isReviewable()).isTrue();
    }

    @Test
    void returnsEmptyListForEmptyOrBlankDiff() {
        assertThat(parser.parseDiff("")).isEmpty();
        assertThat(parser.parseDiff("   \n\t  ")).isEmpty();
        assertThat(parser.parseDiff(null)).isEmpty();
    }

    @Test
    void marksUnsupportedFileTypesAsNotReviewable() {
        String diff = """
                diff --git a/README.md b/README.md
                index 1111111..2222222 100644
                --- a/README.md
                +++ b/README.md
                @@ -1,1 +1,2 @@
                 # CodePilot
                +Documentation update
                diff --git a/yarn.lock b/yarn.lock
                index 1111111..2222222 100644
                --- a/yarn.lock
                +++ b/yarn.lock
                @@ -1,1 +1,2 @@
                 # lock
                +pkg@1.0.0:
                """;

        List<DiffParser.ParsedFile> files = parser.parseDiff(diff);

        assertThat(files).hasSize(2);
        assertThat(files).allSatisfy(file -> assertThat(file.isReviewable()).isFalse());
    }

    @Test
    void marksMissingPatchContentAsNotReviewable() {
        String diff = """
                diff --git a/LargeGenerated.java b/LargeGenerated.java
                index 1111111..2222222 100644
                """;

        List<DiffParser.ParsedFile> files = parser.parseDiff(diff);

        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.isIncomplete()).isTrue();
            assertThat(file.isReviewable()).isFalse();
        });
    }

    @Test
    void marksDeletedFilesAsNotReviewableAndPureRenamesWithoutPatchAsNotReviewable() {
        String diff = """
                diff --git a/Deleted.java b/Deleted.java
                deleted file mode 100644
                index 1111111..0000000
                --- a/Deleted.java
                +++ /dev/null
                @@ -1,2 +0,0 @@
                -class Deleted {}
                diff --git a/OldName.java b/NewName.java
                similarity index 100%
                rename from OldName.java
                rename to NewName.java
                """;

        List<DiffParser.ParsedFile> files = parser.parseDiff(diff);

        assertThat(files).hasSize(2);
        assertThat(files.get(0).isDeleted()).isTrue();
        assertThat(files.get(0).isReviewable()).isFalse();
        assertThat(files.get(1).isRenamed()).isTrue();
        assertThat(files.get(1).isReviewable()).isFalse();
    }
}
