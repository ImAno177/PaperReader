plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}

tasks.register("hostUnitTest") {
    group = "verification"
    description = "Runs the deterministic JVM unit-test gate for every production module."
    dependsOn(
        ":extension-api:testDebugUnitTest",
        ":logic:testDebugUnitTest",
        ":app:testDebugUnitTest",
    )
}

abstract class CheckNoSourceComments : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @TaskAction
    fun check() {
        val root = rootDirectory.get().asFile
        val violations = sources.files.sortedBy { it.path }.flatMap { file ->
            val text = file.readText().replace("\r\n", "\n")
            commentOffsets(text, file.extension).map { offset ->
                val line = text.substring(0, offset).count { it == '\n' } + 1
                "${file.relativeTo(root).invariantSeparatorsPath}:$line"
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Comments are not allowed in PaperReader sources:\n" + violations.joinToString("\n") +
                    "\nDelete each comment. Say what the code does through names, types, and small " +
                    "functions. Put lasting rationale, upstream sources, and trade-offs in docs/SPEC.md " +
                    "or docs/ARCHITECTURE.md. Record a security-scanner false positive as a dismissal " +
                    "with its reason in GitHub code scanning, not as an inline suppression.",
            )
        }
    }

    private fun commentOffsets(text: String, extension: String): List<Int> = when (extension) {
        "pro" -> lineOffsets(text).filter { text.startsWith("#", text.indexOfNonBlank(it)) }
        "xml" -> Regex("<!--").findAll(text).map { it.range.first }.toList()
        else -> codeCommentOffsets(text, templates = extension == "kt" || extension == "kts")
    }

    private fun lineOffsets(text: String): List<Int> =
        listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }

    private fun String.indexOfNonBlank(from: Int): Int {
        var index = from
        while (index < length && (this[index] == ' ' || this[index] == '\t')) index++
        return index
    }

    private fun codeCommentOffsets(text: String, templates: Boolean): List<Int> {
        val found = mutableListOf<Int>()
        val templateDepths = ArrayDeque<Pair<Int, Boolean>>()
        var inString = false
        var rawString = false
        var depth = 0
        var i = 0
        while (i < text.length) {
            if (inString) {
                when {
                    rawString && text.startsWith("\"\"\"", i) -> {
                        while (text.startsWith("\"\"\"\"", i)) i++
                        i += 3
                        inString = false
                    }
                    !rawString && text[i] == '\\' -> i += 2
                    !rawString && text[i] == '"' -> {
                        i++
                        inString = false
                    }
                    templates && text.startsWith("\${", i) -> {
                        templateDepths.addLast(depth to rawString)
                        depth = 0
                        inString = false
                        i += 2
                    }
                    else -> i++
                }
                continue
            }
            when {
                text.startsWith("//", i) -> {
                    found += i
                    i = text.indexOf('\n', i).let { if (it < 0) text.length else it }
                }
                text.startsWith("/*", i) -> {
                    found += i
                    var level = 1
                    i += 2
                    while (i < text.length && level > 0) {
                        when {
                            templates && text.startsWith("/*", i) -> { level++; i += 2 }
                            text.startsWith("*/", i) -> { level--; i += 2 }
                            else -> i++
                        }
                    }
                }
                text.startsWith("\"\"\"", i) -> { inString = true; rawString = true; i += 3 }
                text[i] == '"' -> { inString = true; rawString = false; i++ }
                text[i] == '\'' -> {
                    i++
                    while (i < text.length && text[i] != '\'') i += if (text[i] == '\\') 2 else 1
                    i++
                }
                templateDepths.isNotEmpty() && text[i] == '{' -> { depth++; i++ }
                templateDepths.isNotEmpty() && text[i] == '}' && depth > 0 -> { depth--; i++ }
                templateDepths.isNotEmpty() && text[i] == '}' -> {
                    val (outerDepth, outerRaw) = templateDepths.removeLast()
                    depth = outerDepth
                    rawString = outerRaw
                    inString = true
                    i++
                }
                else -> i++
            }
        }
        return found
    }
}

val checkNoSourceComments = tasks.register<CheckNoSourceComments>("checkNoSourceComments") {
    group = "verification"
    description = "Fails when Kotlin, Java, AIDL, Android XML, Gradle, or ProGuard sources contain comments."
    rootDirectory.set(layout.projectDirectory)
    sources.from(
        fileTree(layout.projectDirectory) {
            include("app/**", "logic/**", "extension-api/**", "*.kts")
            exclude("**/build/**")
        }.filter { file -> file.extension in setOf("kt", "kts", "java", "aidl", "pro", "xml") },
    )
}

tasks.register("hostLint") {
    group = "verification"
    description = "Runs the source-comment check and the debug lint gate for every production module."
    dependsOn(
        checkNoSourceComments,
        ":extension-api:lintDebug",
        ":logic:lintDebug",
        ":app:lintDebug",
    )
}

tasks.register("hostConnectedTest") {
    group = "verification"
    description = "Runs connected Android tests for every production module."
    dependsOn(
        ":extension-api:connectedDebugAndroidTest",
        ":logic:connectedDebugAndroidTest",
        ":app:connectedDebugAndroidTest",
    )
}

tasks.register("coverageReport") {
    group = "verification"
    description = "Runs deterministic JVM tests and generates debug coverage for every production module."
    dependsOn(
        ":extension-api:jacocoDebugUnitTestReport",
        ":logic:jacocoDebugUnitTestReport",
        ":app:jacocoDebugUnitTestReport",
    )
}

tasks.register("coverageConnectedReport") {
    group = "verification"
    description = "Runs connected tests and enforces configured merged coverage thresholds."
    dependsOn(
        ":extension-api:verifyDebugCombinedCoverage",
        ":logic:jacocoDebugCombinedReport",
        ":app:jacocoDebugCombinedReport",
    )
}
