package io.stackvibed.viewer.registry.internal

import io.stackvibed.model.view.AddWorkspaceRequest
import io.stackvibed.model.workspace.ProjectInfo
import io.stackvibed.model.workspace.WorkspaceConfig
import io.stackvibed.viewer.recordings.RecordingRepositories
import io.stackvibed.viewer.registry.OpenWorkspace
import io.stackvibed.viewer.registry.UnknownWorkspaceException
import io.stackvibed.viewer.registry.WorkspaceEntry
import io.stackvibed.viewer.registry.WorkspaceRegistry
import io.stackvibed.viewer.workspace.WorkspaceStores
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
 * The workspace list kept in [listFile] (spec §2-§5). The file is read once at construction and written on every
 * change; two viewers sharing it are not guarded (the last writer wins).
 */
class FileWorkspaceRegistry(listFile: Path) : WorkspaceRegistry {
    private val file = WorkspaceListFile(listFile)
    private var entries: List<WorkspaceEntry>
    private var lastOpened: String?
    private val open = mutableMapOf<String, OpenWorkspace>()

    init {
        val (read, last) = file.read()
        entries = read
        lastOpened = last
    }

    @Synchronized
    override fun list(): List<WorkspaceEntry> = entries

    @Synchronized
    override fun lastOpened(): String? = lastOpened

    @Synchronized
    override fun add(request: AddWorkspaceRequest): WorkspaceEntry {
        val kind = classify(request.folder)
        val (root, workspace) = when (kind) {
            is FolderKind.ProjectRoot -> kind.root to kind.root.resolve(STACK)
            is FolderKind.StackFolder -> (kind.stack.parent ?: kind.stack) to kind.stack
            is FolderKind.NewRoot -> kind.root to kind.root.resolve(STACK)
        }
        val name = request.name?.trim()?.takeIf { it.isNotEmpty() }
            ?: if (kind is FolderKind.NewRoot) folderSlug(root) else defaultName(root, workspace)
        val recordings = request.recordings?.takeIf { it.isNotBlank() }?.let(::parseFolder) ?: root.resolve(RECORDINGS)

        requireFreeName(name)
        requireUnlisted(workspace)
        if (kind is FolderKind.NewRoot) createWorkspace(workspace, name)

        return append(WorkspaceEntry(name, workspace, recordings))
    }

    @Synchronized
    override fun rename(name: String, newName: String): WorkspaceEntry {
        val entry = find(name)
        if (newName != name) requireFreeName(newName)
        val renamed = entry.copy(name = newName)
        entries = entries.map { if (it.name == name) renamed else it }
        if (lastOpened == name) lastOpened = newName
        open.remove(name)
        file.write(entries, lastOpened)
        return renamed
    }

    @Synchronized
    override fun remove(name: String) {
        find(name)
        entries = entries.filter { it.name != name }
        if (lastOpened == name) lastOpened = null
        open.remove(name)
        file.write(entries, lastOpened)
    }

    @Synchronized
    override fun open(name: String): OpenWorkspace {
        open[name]?.let { return it }
        val entry = find(name)
        val opened = OpenWorkspace(entry, WorkspaceStores.open(entry.workspace), RecordingRepositories.open(entry.recordings, entry.workspace))
        open[name] = opened
        return opened
    }

    @Synchronized
    override fun markOpened(name: String) {
        find(name)
        if (lastOpened == name) return
        lastOpened = name
        file.write(entries, lastOpened)
    }

    @Synchronized
    override fun registerStartup(workspace: Path, recordings: Path?): WorkspaceEntry {
        val folder = workspace.toAbsolutePath().normalize()
        val listed = entries.firstOrNull { identity(it.workspace) == identity(folder) }
        if (listed != null) {
            markOpened(listed.name)
            return listed
        }
        val root = folder.parent ?: folder
        val base = defaultName(root, folder)
        val name = generateSequence(1) { it + 1 }
            .map { if (it == 1) base else "$base-$it" }
            .first { candidate -> entries.none { it.name == candidate } }
        val entry = WorkspaceEntry(name, folder, recordings?.toAbsolutePath()?.normalize() ?: root.resolve(RECORDINGS))
        entries = entries + entry
        lastOpened = name
        file.write(entries, lastOpened)
        return entry
    }

    private fun append(entry: WorkspaceEntry): WorkspaceEntry {
        entries = entries + entry
        file.write(entries, lastOpened)
        return entry
    }

    private fun find(name: String): WorkspaceEntry = entries.firstOrNull { it.name == name } ?: throw UnknownWorkspaceException(name)

    private fun requireFreeName(name: String) {
        require(SLUG.matches(name)) { "A name may use lowercase letters, digits and '-', and starts with a letter or digit, but was '$name'" }
        require(entries.none { it.name == name }) { "A workspace named '$name' is already in the list" }
    }

    private fun requireUnlisted(workspace: Path) {
        val taken = entries.firstOrNull { identity(it.workspace) == identity(workspace) }
        require(taken == null) { "This folder is already in the list as '${taken?.name}'" }
    }

    /** The slug of `project.name`, else of the root folder's name, else `workspace`. */
    private fun defaultName(root: Path, workspace: Path): String {
        val projectName = WorkspaceStores.open(workspace).load().config.project.name
        return slug(projectName).ifEmpty { folderSlug(root) }
    }

    private fun folderSlug(root: Path): String = slug(root.fileName?.toString().orEmpty()).ifEmpty { "workspace" }

    /** Same folder however it is written: absolute, normalized and, when it exists, with symlinks and case resolved. */
    private fun identity(path: Path): Path {
        val normalized = path.toAbsolutePath().normalize()
        return if (Files.exists(normalized)) normalized.toRealPath() else normalized
    }

    private fun createWorkspace(workspace: Path, projectName: String) {
        Files.createDirectories(workspace)
        val target = workspace.resolve(WORKSPACE_FILE)
        val temp = Files.createTempFile(workspace, ".$WORKSPACE_FILE-", ".tmp")
        try {
            Files.writeString(temp, yaml.writeValueAsString(WorkspaceConfig(ProjectInfo(projectName))))
            try {
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private companion object {
        const val STACK = "stack"
        const val RECORDINGS = "recordings"
        const val WORKSPACE_FILE = "workspace.yaml"

        val yaml: YAMLMapper = YAMLMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
            .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build()
    }
}
