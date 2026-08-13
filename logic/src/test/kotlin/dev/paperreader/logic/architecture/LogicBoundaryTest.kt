package dev.paperreader.logic.architecture

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicBoundaryTest {
    private val forbiddenImports = listOf(
        "androidx.compose.",
        "androidx.activity.",
        "androidx.fragment.",
        "androidx.lifecycle.ViewModel",
        "android.app.Activity",
        "android.view.",
        "android.widget.",
    )

    @Test
    fun `logic production source contains no UI framework imports`() {
        val logicRoot = findLogicRoot()
        val sourceRoot = logicRoot.resolve("src/main")
        assertTrue("Missing logic source root: $sourceRoot", Files.isDirectory(sourceRoot))

        val violations = Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension in setOf("kt", "java") }
                .flatMap { file ->
                    val text = file.readText()
                    forbiddenImports.filter(text::contains)
                        .map { forbidden -> "$file imports $forbidden" }
                        .stream()
                }
                .toList()
        }

        assertTrue("UI dependencies leaked into :logic:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `logic has no reverse dependency on app`() {
        val buildFile = findLogicRoot().resolve("build.gradle.kts").readText()
        assertFalse(buildFile.contains("project(\":app\")"))
        assertFalse(buildFile.contains("androidx.compose"))
    }

    @Test
    fun `app cannot bypass the public logic boundary`() {
        val appSource = findLogicRoot().parent.resolve("app/src/main")
        val forbidden = listOf(
            "dev.paperreader.logic.data.",
            "dev.paperreader.logic.network.",
            "dev.paperreader.logic.provider.builtin.",
            "dev.paperreader.logic.reader.ExtractionService",
            "dev.paperreader.logic.reader.PdfTextExtractor",
        )
        val violations = if (!Files.isDirectory(appSource)) {
            emptyList()
        } else {
            Files.walk(appSource).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.extension in setOf("kt", "java") }
                    .flatMap { file ->
                        val text = file.readText()
                        forbidden.filter(text::contains).map { "$file imports $it" }.stream()
                    }
                    .toList()
            }
        }

        assertTrue("The UI bypassed PaperReaderLogic:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `internal layers follow Mihon-style dependency direction`() {
        val kotlinRoot = findLogicRoot().resolve("src/main/kotlin/dev/paperreader/logic")
        val rules = mapOf(
            "domain" to listOf(
                "dev.paperreader.logic.data.",
                "dev.paperreader.logic.network.",
                "dev.paperreader.logic.provider.builtin.",
                "androidx.room.",
            ),
            "usecase" to listOf(
                "dev.paperreader.logic.data.",
                "dev.paperreader.logic.network.",
                "dev.paperreader.logic.provider.builtin.",
                "androidx.room.",
                "android.content.",
            ),
            "data" to listOf(
                "dev.paperreader.logic.usecase.",
                "dev.paperreader.logic.network.",
                "dev.paperreader.logic.provider.builtin.",
                "dev.paperreader.logic.reader.",
            ),
        )
        val violations = rules.flatMap { (directory, forbidden) ->
            findViolations(kotlinRoot.resolve(directory), forbidden)
        }

        assertTrue("Layer direction was bypassed:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private fun findViolations(root: Path, forbidden: List<String>): List<String> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension in setOf("kt", "java") }
                .flatMap { file ->
                    val text = file.readText()
                    forbidden.filter(text::contains).map { "$file imports $it" }.stream()
                }
                .toList()
        }
    }

    private fun findLogicRoot(): Path {
        val workingDirectory = Paths.get("").toAbsolutePath()
        return if (workingDirectory.fileName.toString() == "logic") {
            workingDirectory
        } else {
            workingDirectory.resolve("logic")
        }
    }
}
