package io.stackvibed.viewer.registry.internal

import java.nio.file.Files
import java.nio.file.Path

/** What a pasted folder is (spec §4). */
sealed interface FolderKind {
    /** Holds `stack/workspace.yaml`. */
    data class ProjectRoot(val root: Path) : FolderKind

    /** Holds `workspace.yaml` itself. */
    data class StackFolder(val stack: Path) : FolderKind

    /** Empty or missing: becomes a project root. */
    data class NewRoot(val root: Path) : FolderKind
}

private const val WORKSPACE_FILE = "workspace.yaml"
private const val STACK = "stack"

/** Strips surrounding whitespace and one pair of double quotes (as pasted from Explorer); the path must be absolute. */
fun parseFolder(input: String): Path {
    var text = input.trim()
    if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) text = text.substring(1, text.length - 1).trim()
    val path = Path.of(text)
    require(text.isNotEmpty() && path.isAbsolute) { "Use a full path, such as D:\\projects\\sam or /Users/me/sam" }
    return path.normalize()
}

fun classify(input: String): FolderKind {
    val folder = parseFolder(input)
    if (!Files.exists(folder)) return FolderKind.NewRoot(folder)
    val refusal = IllegalArgumentException("Expected a project folder with `stack/workspace.yaml`, a `stack/` folder, or an empty folder")
    if (!Files.isDirectory(folder)) throw refusal
    return when {
        Files.isRegularFile(folder.resolve(STACK).resolve(WORKSPACE_FILE)) -> FolderKind.ProjectRoot(folder)
        Files.isRegularFile(folder.resolve(WORKSPACE_FILE)) -> FolderKind.StackFolder(folder)
        Files.list(folder).use { it.findAny().isEmpty } -> FolderKind.NewRoot(folder)
        else -> throw refusal
    }
}
