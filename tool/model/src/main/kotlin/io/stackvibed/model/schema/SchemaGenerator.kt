package io.stackvibed.model.schema

import com.github.victools.jsonschema.generator.CustomPropertyDefinition
import com.github.victools.jsonschema.generator.Option
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerator as VictoolsGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.module.jackson.JacksonOption
import com.github.victools.jsonschema.module.jackson.JacksonSchemaModule
import io.stackvibed.model.RecordingEvent
import io.stackvibed.model.RecordingManifest
import io.stackvibed.model.view.Flow
import io.stackvibed.model.view.WorkspaceView
import io.stackvibed.model.workspace.BoardLayout
import io.stackvibed.model.workspace.ComponentDefinition
import io.stackvibed.model.workspace.ConnectionDefinition
import io.stackvibed.model.workspace.ScenarioDefinition
import io.stackvibed.model.workspace.WorkspaceConfig
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.lang.reflect.Field
import java.nio.file.Path
import kotlin.reflect.jvm.kotlinProperty

/** Aggregates every top-level model type so that one schema document covers all of them. */
@Suppress("unused")
class ModelSchemaRoot(
    val recordingEvent: RecordingEvent,
    val recordingManifest: RecordingManifest,
    val workspaceConfig: WorkspaceConfig,
    val componentDefinition: ComponentDefinition,
    val connectionDefinition: ConnectionDefinition,
    val scenarioDefinition: ScenarioDefinition,
    val boardLayout: BoardLayout,
    val workspaceView: WorkspaceView,
    val flow: Flow,
)

/** Generates the JSON schema of the model. Output is pretty-printed with sorted keys so it is stable across runs. */
object SchemaGenerator {
    private val mapper: JsonMapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()

    fun generate(): String {
        val config = SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(JacksonSchemaModule(JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY))
            .with(Option.EXTRA_OPEN_API_FORMAT_VALUES, Option.MAP_VALUES_AS_ADDITIONAL_PROPERTIES)
            .also { builder ->
                // Kotlin nullability is the source of truth: non-null properties are required, nullable ones may be null.
                builder.forFields()
                    .withRequiredCheck { !isNullable(it.rawMember) }
                    .withNullableCheck { isNullable(it.rawMember) }
                    .withCustomDefinitionProvider { field, _ ->
                        // Java generics erase Kotlin's `List<String?>`, so describe nullable string items by hand.
                        if (hasNullableStringItems(field.rawMember)) {
                            val array = mapper.createObjectNode().put("type", "array")
                            array.putObject("items").putArray("type").add("string").add("null")
                            CustomPropertyDefinition(array)
                        } else {
                            null
                        }
                    }
            }
            .build()
        val schema = VictoolsGenerator(config).generateSchema(ModelSchemaRoot::class.java)
        return mapper.writeValueAsString(sorted(schema)).replace("\r\n", "\n") + "\n"
    }

    private fun isNullable(field: Field): Boolean = field.kotlinProperty?.returnType?.isMarkedNullable ?: false

    private fun hasNullableStringItems(field: Field): Boolean {
        val item = field.kotlinProperty?.returnType?.arguments?.singleOrNull()?.type ?: return false
        return item.isMarkedNullable && item.classifier == String::class
    }

    private fun sorted(node: JsonNode): JsonNode = when {
        node is ObjectNode -> {
            val result = mapper.createObjectNode()
            node.properties().sortedBy { it.key }.forEach { (key, value) ->
                result.set(key, if (key == "required") sortedStrings(value) else sorted(value))
            }
            result
        }
        node.isArray -> mapper.createArrayNode().also { array -> node.forEach { array.add(sorted(it)) } }
        else -> node
    }

    private fun sortedStrings(node: JsonNode): JsonNode =
        if (node.isArray) {
            val names: List<String> = node.values().map { it.asString() }
            mapper.createArrayNode().also { array -> names.sorted().forEach { array.add(it) } }
        } else {
            node
        }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "usage: SchemaGenerator <output-file>" }
        val output = Path.of(args[0])
        output.parent?.let { Files.createDirectories(it) }
        Files.writeString(output, generate())
    }
}
