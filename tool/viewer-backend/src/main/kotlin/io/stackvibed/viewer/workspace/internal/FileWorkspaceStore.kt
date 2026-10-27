package io.stackvibed.viewer.workspace.internal

import com.fasterxml.jackson.annotation.JsonInclude
import io.stackvibed.model.view.WorkspaceProblem
import io.stackvibed.model.workspace.BoardLayout
import io.stackvibed.model.workspace.ComponentDefinition
import io.stackvibed.model.workspace.ConnectionDefinition
import io.stackvibed.model.workspace.ConnectionState
import io.stackvibed.model.workspace.ProjectInfo
import io.stackvibed.model.workspace.ScenarioDefinition
import io.stackvibed.model.workspace.WorkspaceConfig
import io.stackvibed.viewer.ViewerPaths
import io.stackvibed.viewer.workspace.LoadedWorkspace
import io.stackvibed.viewer.workspace.WorkspaceStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.dataformat.yaml.YAMLWriteFeature
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * The workspace folder on disk (spec §8):
 * `workspace.yaml`, `components/<id>.yaml`, `connections/<from>--<to>.yaml`, `scenarios/<id>.yaml`,
 * `notes/<id>.md`, `boards/<board>.layout.json`.
 *
 * Reading is forgiving (hand-edited files may be broken: a bad file is reported as a problem and the rest loads);
 * writing is strict (ids are validated before anything touches the disk) and atomic.
 */
@Component
class FileWorkspaceStore(paths: ViewerPaths) : WorkspaceStore {
    private val root: Path = paths.workspace.toAbsolutePath().normalize()

    private val yaml: YAMLMapper = YAMLMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .disable(YAMLWriteFeature.WRITE_DOC_START_MARKER)
        .enable(YAMLWriteFeature.MINIMIZE_QUOTES)
        .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_NULL) }
        .build()

    private val json: JsonMapper = JsonMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .enable(SerializationFeature.INDENT_OUTPUT)
        .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_NULL) }
        .build()

    override fun load(): LoadedWorkspace {
        val problems = mutableListOf<WorkspaceProblem>()
        val config = loadConfig(problems)

        val components = loadDirectory(COMPONENTS, YAML_EXT, problems) { id, text ->
            val def = yaml.readValue(text, ComponentDefinition::class.java)
            require(def.id == id) { "id '${def.id}' does not match the file name '$id'" }
            def
        }
        val connections = loadDirectory(CONNECTIONS, YAML_EXT, problems) { id, text ->
            val def = yaml.readValue(text, ConnectionDefinition::class.java)
            require(isSlug(def.from) && isSlug(def.to)) { "'from' and 'to' must be ids (lowercase letters, digits and '-')" }
            require(def.id == id) { "endpoints '${def.id}' do not match the file name '$id'" }
            require(def.state != ConnectionState.DISCOVERED) { "discovered connections are never stored" }
            def
        }
        val scenarios = loadDirectory(SCENARIOS, YAML_EXT, problems) { id, text ->
            val def = yaml.readValue(text, ScenarioDefinition::class.java)
            require(def.id == id) { "id '${def.id}' does not match the file name '$id'" }
            def
        }
        loadDirectory(BOARDS, LAYOUT_EXT, problems) { _, text -> json.readValue(text, BoardLayout::class.java) }

        return LoadedWorkspace(config, components, connections, scenarios, problems, notedTargets())
    }

    override fun saveComponent(def: ComponentDefinition) {
        requireSlug(def.id, "component id")
        writeAtomic(root.resolve(COMPONENTS).resolve(def.id + YAML_EXT), yaml.writeValueAsString(def))
    }

    override fun saveConnection(def: ConnectionDefinition) {
        requireSlug(def.from, "connection source")
        requireSlug(def.to, "connection target")
        require(def.state != ConnectionState.DISCOVERED) { "discovered connections are never persisted" }
        writeAtomic(root.resolve(CONNECTIONS).resolve(def.id + YAML_EXT), yaml.writeValueAsString(def))
    }

    override fun readNote(targetId: String): String? {
        requireSlug(targetId, "note target")
        val file = root.resolve(NOTES).resolve(targetId + NOTE_EXT)
        return if (Files.isRegularFile(file)) Files.readString(file) else null
    }

    override fun writeNote(targetId: String, markdown: String) {
        requireSlug(targetId, "note target")
        writeAtomic(root.resolve(NOTES).resolve(targetId + NOTE_EXT), markdown)
    }

    override fun readLayout(board: String): BoardLayout? {
        requireSlug(board, "board name")
        val file = root.resolve(BOARDS).resolve(board + LAYOUT_EXT)
        if (!Files.isRegularFile(file)) return null
        return try {
            json.readValue(Files.readString(file), BoardLayout::class.java)
        } catch (e: Exception) {
            // The layout is only remembered UI state: a broken file means "no layout" (load() reports it as a problem).
            log.warn("Ignoring unreadable layout {}: {}", file, describe(e))
            null
        }
    }

    override fun writeLayout(board: String, layout: BoardLayout) {
        requireSlug(board, "board name")
        writeAtomic(root.resolve(BOARDS).resolve(board + LAYOUT_EXT), json.writeValueAsString(layout) + "\n")
    }

    private fun loadConfig(problems: MutableList<WorkspaceProblem>): WorkspaceConfig {
        val default = WorkspaceConfig(ProjectInfo(root.fileName?.toString() ?: "workspace"))
        val file = root.resolve(WORKSPACE_FILE)
        if (!Files.isRegularFile(file)) return default
        return try {
            yaml.readValue(Files.readString(file), WorkspaceConfig::class.java)
        } catch (e: Exception) {
            problems += WorkspaceProblem(WORKSPACE_FILE, describe(e))
            default
        }
    }

    /** Parses every `<id><ext>` file of [directory] in file-name order; a file that cannot be read becomes a problem. */
    private fun <T> loadDirectory(
        directory: String,
        ext: String,
        problems: MutableList<WorkspaceProblem>,
        parse: (id: String, text: String) -> T,
    ): List<T> = filesIn(directory, ext).mapNotNull { file ->
        val name = file.fileName.toString()
        try {
            val id = name.removeSuffix(ext)
            require(isSlug(id)) { "file name is not a valid id (lowercase letters, digits and '-')" }
            parse(id, Files.readString(file))
        } catch (e: Exception) {
            problems += WorkspaceProblem("$directory/$name", describe(e))
            null
        }
    }

    private fun notedTargets(): Set<String> =
        filesIn(NOTES, NOTE_EXT).map { it.fileName.toString().removeSuffix(NOTE_EXT) }.filter(::isSlug).toSet()

    private fun filesIn(directory: String, ext: String): List<Path> {
        val dir = root.resolve(directory)
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(ext) }
                .sorted(compareBy { it.fileName.toString() })
                .toList()
        }
    }

    /** Writes next to the target and renames over it, so readers never see a half-written file. */
    private fun writeAtomic(target: Path, content: String) {
        val dir = target.parent
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, ".${target.fileName}-", ".tmp")
        try {
            Files.writeString(temp, content)
            try {
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun describe(e: Exception): String {
        val message = if (e is JacksonException) e.originalMessage else e.message
        return (message ?: e::class.simpleName ?: "unreadable").lineSequence().first().take(300)
    }

    private fun requireSlug(value: String, what: String) =
        require(isSlug(value)) { "$what must match ${SLUG.pattern} but was '$value'" }

    private fun isSlug(value: String) = SLUG.matches(value)

    private companion object {
        val log = LoggerFactory.getLogger(FileWorkspaceStore::class.java)
        val SLUG = Regex("^[a-z0-9][a-z0-9-]*$")

        const val WORKSPACE_FILE = "workspace.yaml"
        const val COMPONENTS = "components"
        const val CONNECTIONS = "connections"
        const val SCENARIOS = "scenarios"
        const val NOTES = "notes"
        const val BOARDS = "boards"
        const val YAML_EXT = ".yaml"
        const val NOTE_EXT = ".md"
        const val LAYOUT_EXT = ".layout.json"
    }
}
