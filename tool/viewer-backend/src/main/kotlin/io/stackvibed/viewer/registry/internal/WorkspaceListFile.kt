package io.stackvibed.viewer.registry.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.stackvibed.viewer.registry.WorkspaceEntry
import org.slf4j.LoggerFactory
import tools.jackson.databind.MapperFeature
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.dataformat.yaml.YAMLWriteFeature
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * The `workspaces.yaml` file (spec §3): the entries in the order they were added plus `lastOpened`.
 * A missing file is an empty list; a file that cannot be read is logged and read as empty, and the first write after
 * that keeps it as `<file>.broken`. Writes are atomic. Paths are written with forward slashes.
 */
class WorkspaceListFile(private val path: Path) {
    private var broken = false

    fun read(): Pair<List<WorkspaceEntry>, String?> {
        if (!Files.isRegularFile(path)) return emptyList<WorkspaceEntry>() to null
        return try {
            val stored = yaml.readValue(Files.readString(path), StoredList::class.java)
            stored.workspaces.map { WorkspaceEntry(it.name, Path.of(it.workspace), Path.of(it.recordings)) } to stored.lastOpened
        } catch (e: Exception) {
            log.warn("Ignoring unreadable workspace list {}: {}", path, e.message?.lineSequence()?.first()?.take(300))
            broken = true
            emptyList<WorkspaceEntry>() to null
        }
    }

    fun write(entries: List<WorkspaceEntry>, lastOpened: String?) {
        val stored = StoredList(lastOpened, entries.map { StoredEntry(it.name, slashed(it.workspace), slashed(it.recordings)) })
        val dir = path.toAbsolutePath().parent
        Files.createDirectories(dir)
        if (broken) {
            Files.move(path, path.resolveSibling(path.fileName.toString() + ".broken"), REPLACE_EXISTING)
            broken = false
        }
        val temp = Files.createTempFile(dir, ".${path.fileName}-", ".tmp")
        try {
            Files.writeString(temp, yaml.writeValueAsString(stored))
            try {
                Files.move(temp, path, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun slashed(p: Path) = p.toString().replace('\\', '/')

    private data class StoredList(val lastOpened: String? = null, val workspaces: List<StoredEntry> = emptyList())

    private data class StoredEntry(val name: String, val workspace: String, val recordings: String)

    private companion object {
        val log = LoggerFactory.getLogger(WorkspaceListFile::class.java)

        val yaml: YAMLMapper = YAMLMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_NULL) }
            .build()
    }
}
