@file:OptIn(ExperimentalSerializationApi::class)

package io.github.castab.fionas.commerce.http

import com.fasterxml.jackson.databind.JsonNode
import io.github.castab.commerce.runtime.http.CommerceJson
import io.github.castab.commerce.runtime.http.ErrorCategory
import io.github.castab.commerce.runtime.http.ErrorResponse
import io.github.castab.commerce.runtime.http.ValidationErrorResponse
import io.github.castab.commerce.runtime.http.ValidationViolationResponse
import io.github.castab.commerce.runtime.http.jsonBody
import io.github.castab.commerce.runtime.offering.OfferingAvailabilityDto
import io.github.castab.commerce.runtime.offering.OfferingDto
import io.github.castab.commerce.runtime.offering.OfferingPriceDto
import io.github.castab.commerce.runtime.offering.OfferingSelectionStateDto
import io.github.castab.commerce.runtime.offering.OfferingsCatalogDto
import io.github.castab.commerce.runtime.offering.offeringsOpenApiRenderer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.nonNullOriginal
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.serializer
import org.http4k.contract.ContractRenderer
import org.http4k.contract.ErrorResponseRenderer
import org.http4k.contract.RouteMetaDsl
import org.http4k.contract.jsonschema.JsonSchema
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.ApiRenderer
import org.http4k.contract.openapi.OpenApiVersion
import org.http4k.contract.openapi.v3.Api
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.contract.openapi.v3.OpenApi3ApiRenderer
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.format.Jackson
import org.http4k.lens.LensFailure
import java.util.concurrent.ConcurrentHashMap

const val API_TITLE = "Fiona's Commerce API"

/**
 * Renders the Fiona API contract as an OpenAPI 3.1 document, the one served at
 * [OPENAPI_PATH] and generated into `build/openapi` alike.
 *
 * - No `servers`: the document describes paths only, so it is the same for every
 *   deployment, and Swagger UI calls the origin that served it.
 * - Schemas come from the transport DTOs' kotlinx.serialization descriptors, the wire
 *   format itself ([KotlinxSchemas]), including runtime authorization DTOs. The Offerings
 *   catalog uses commerce-runtime's `offeringsOpenApiRenderer` ([OfferingsSchemas]).
 * - Errors the contract itself detects are left to commerce-runtime ([RuntimeErrorHandling]).
 */
fun fionaOpenApi(version: String): ContractRenderer =
    OpenApi3(
        apiInfo =
            ApiInfo(
                title = API_TITLE,
                version = version,
                description =
                    "The HTTP API of the fionas-commerce application, the commerce backend of Fiona's Ice Cream and its " +
                        "catering business. Errors contain `code` and `message`; validation failures may also contain " +
                        "an optional `violations` list of objects with stable string `code` values. Codes are machine-readable; " +
                        "`message` is diagnostic text for people and may change. The Offerings catalog routes " +
                        "are implemented by commerce-runtime's reusable Offerings capability; Fiona chooses the catalog " +
                        "and where it is served. The runtime authorization administration capability is mounted at " +
                        "`/admin/access`. The runtime's `/health` and `/ready` are not part of this API.",
            ),
        json = CommerceJson,
        apiRenderer = KotlinxSchemas(OpenApi3ApiRenderer(CommerceJson), OfferingsSchemas()),
        errorResponseRenderer = RuntimeErrorHandling,
        version = OpenApiVersion._3_1_0,
    )

/**
 * Leaves every failure the contract detects to commerce-runtime's `CommerceErrorHandling`,
 * which owns the error contract: an unreadable input is rethrown as the [LensFailure] it
 * is (`400 malformed_request`), and an unmatched request is an empty `404` (`not_found`).
 * A contract would otherwise answer with http4k's own error bodies.
 */
private object RuntimeErrorHandling : ErrorResponseRenderer {
    override fun badRequest(lensFailure: LensFailure): Response = throw lensFailure

    override fun notFound(): Response = Response(Status.NOT_FOUND)
}

private val errorBody = jsonBody(ErrorResponse.serializer())
private val validationErrorBody = jsonBody(ValidationErrorResponse.serializer())

/** The example message of an `internal_failure`, which never describes its cause. */
internal const val INTERNAL_FAILURE = "The request could not be completed"

/**
 * Documents that the route answers [category] with commerce-runtime's error body. Status
 * and code come from the runtime's own [ErrorCategory], never restated here.
 */
fun RouteMetaDsl.returningError(
    category: ErrorCategory,
    description: String,
    exampleMessage: String,
    violations: List<ValidationViolationResponse>? = null,
) {
    if (category == ErrorCategory.VALIDATION_FAILED) {
        returning(
            category.status,
            validationErrorBody to ValidationErrorResponse(category.code, exampleMessage, violations),
            "`${category.code}`: $description",
        )
    } else {
        returning(category.status, errorBody to ErrorResponse(category.code, exampleMessage), "`${category.code}`: $description")
    }
}

/**
 * What a transport DTO property means beyond its Kotlin type, for its OpenAPI schema. It
 * belongs on `@Serializable` DTOs in this package only, never on application types, and
 * states only what the server enforces or guarantees. It is read from the DTO's serial
 * descriptor, so it moves and renames with the property it describes. Empty strings and
 * negative numbers mean "not stated".
 */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class ApiProperty(
    val description: String = "",
    val format: String = "",
    val minLength: Int = -1,
    val maxLength: Int = -1,
    val pattern: String = "",
)

/**
 * JSON Schemas for `@Serializable` bodies, derived from their kotlinx.serialization
 * descriptors: property names as serialized, and `required` exactly as [CommerceJson]
 * reads and writes them. [CommerceJson] omits nulls and treats an absent nullable property
 * as null, so a property is required only when it is neither nullable nor defaulted, and a
 * nullable one is described as optional rather than as possibly `null`.
 *
 * http4k 6.58's reflective `ApiRenderer.Auto` cannot render kotlinx.serialization models
 * (it fails to serialize its own schema nodes), and its example-based renderer, [fallback],
 * infers neither `required` nor formats. Anything that is not a serializable class, such
 * as an enum path parameter, still goes to [fallback].
 *
 * The bodies of commerce-runtime's Offerings routes are not Fiona DTOs: they go to
 * [offerings], so the runtime alone describes them.
 *
 * Only what the API's DTOs use is supported: objects whose properties are strings, `Int`s
 * (`integer`, `int32`), booleans, lists of those or of objects, and nested `@Serializable`
 * objects, enums, and sealed inputs with a string discriminator and explicit oneOf variants.
 * Anything else (other numbers, maps, open polymorphism, or nullable list items) fails rendering loudly, rather than publishing a schema that
 * misdescribes the wire format; extend it when a DTO needs more.
 */
private class KotlinxSchemas(
    private val fallback: ApiRenderer<Api<JsonElement>, JsonElement>,
    private val offerings: OfferingsSchemas,
) : ApiRenderer<Api<JsonElement>, JsonElement> by fallback {
    // Definition name → what it describes (a DTO's serial name, or the Offerings
    // capability), so two bodies can never share one component.
    private val definitions = ConcurrentHashMap<String, String>()

    override fun toSchema(
        obj: Any,
        overrideDefinitionId: String?,
        refModelNamePrefix: String?,
    ): JsonSchema<JsonElement> {
        if (offerings.describes(obj)) {
            return offerings.toSchema(obj, overrideDefinitionId, refModelNamePrefix).also { schema ->
                schema.definitions.keys.forEach { own(it, OfferingsSchemas.OWNER) }
            }
        }
        val descriptor =
            serializerOf(obj)?.descriptor?.takeIf { it.kind == StructureKind.CLASS }
                ?: return fallback.toSchema(obj, overrideDefinitionId, refModelNamePrefix)
        val name = refModelNamePrefix.orEmpty() + (overrideDefinitionId ?: descriptor.serialName.substringAfterLast('.'))
        val components = linkedMapOf<String, JsonElement>()
        define(name, descriptor, refModelNamePrefix, components)
        return JsonSchema(reference(name), components)
    }

    /** Adds the component [name] describing [descriptor], and those of the objects it contains, to [components]. */
    private fun define(
        name: String,
        descriptor: SerialDescriptor,
        prefix: String?,
        components: MutableMap<String, JsonElement>,
    ) {
        own(name, descriptor.serialName)
        if (name in components) return
        components[name] = JsonObject(emptyMap()) // Reserved while its properties are described.
        components[name] =
            when (descriptor.kind) {
                StructureKind.CLASS -> objectSchema(descriptor, prefix, components)
                PolymorphicKind.SEALED -> sealedSchema(name, descriptor, prefix, components)
                else -> error("OpenAPI component $name has unsupported kind ${descriptor.kind}")
            }
    }

    /** Describes the actual sealed serializer, including each variant's required discriminator value. */
    private fun sealedSchema(
        name: String,
        descriptor: SerialDescriptor,
        prefix: String?,
        components: MutableMap<String, JsonElement>,
    ): JsonObject {
        val discriminator =
            descriptor.annotations
                .filterIsInstance<JsonClassDiscriminator>()
                .singleOrNull()
                ?.discriminator
                ?: CommerceJson.json.configuration.classDiscriminator
        val variants = descriptor.getElementDescriptor(1)
        val mapping = linkedMapOf<String, String>()
        for (index in 0 until variants.elementsCount) {
            val variant = variants.getElementDescriptor(index)
            check(variant.kind == StructureKind.CLASS) { "Sealed variant ${variant.serialName} must be an object" }
            val tag = variant.serialName
            val variantName = name + "_" + tag.substringAfterLast('.')
            own(variantName, tag)
            components[variantName] = objectSchema(variant, prefix, components, discriminator to tag)
            mapping[tag] = variantName
        }
        return buildJsonObject {
            putJsonArray("oneOf") { mapping.values.forEach { add(reference(it)) } }
            putJsonObject("discriminator") {
                put("propertyName", discriminator)
                putJsonObject("mapping") { mapping.forEach { (tag, variantName) -> put(tag, "#/components/schemas/$variantName") } }
            }
        }
    }

    private fun reference(name: String) = buildJsonObject { put("\$ref", "#/components/schemas/$name") }

    private fun own(
        name: String,
        owner: String,
    ) {
        val existing = definitions.putIfAbsent(name, owner) ?: owner
        check(existing == owner) { "OpenAPI schema $name would describe both $existing and $owner" }
    }

    private fun serializerOf(obj: Any): KSerializer<Any>? =
        if (obj is JsonElement) {
            null
        } else {
            try {
                CommerceJson.json.serializersModule.serializer(obj.javaClass)
            } catch (_: SerializationException) {
                null
            }
        }

    private fun objectSchema(
        descriptor: SerialDescriptor,
        prefix: String?,
        components: MutableMap<String, JsonElement>,
        discriminator: Pair<String, String>? = null,
    ): JsonObject =
        buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                discriminator?.let { (property, tag) ->
                    check((0 until descriptor.elementsCount).none { descriptor.getElementName(it) == property }) {
                        "${descriptor.serialName} declares its serializer's discriminator $property as a property"
                    }
                    putJsonObject(property) {
                        put("type", "string")
                        put("const", tag)
                    }
                }
                for (index in 0 until descriptor.elementsCount) {
                    put(descriptor.getElementName(index), propertySchema(descriptor, index, prefix, components))
                }
            }
            val required =
                (0 until descriptor.elementsCount)
                    .filterNot { descriptor.isElementOptional(it) || descriptor.getElementDescriptor(it).isNullable }
                    .map(descriptor::getElementName) + listOfNotNull(discriminator?.first)
            if (required.isNotEmpty()) putJsonArray("required") { required.forEach(::add) }
        }

    private fun propertySchema(
        owner: SerialDescriptor,
        index: Int,
        prefix: String?,
        components: MutableMap<String, JsonElement>,
    ): JsonObject {
        val property = "${owner.serialName}.${owner.getElementName(index)}"
        val value = owner.getElementDescriptor(index)
        val facts = owner.getElementAnnotations(index).filterIsInstance<ApiProperty>().singleOrNull()
        if (facts != null) {
            check(facts.minLength < 0 && facts.maxLength < 0 || value.kind == PrimitiveKind.STRING) {
                "$property states a length, but is not a string"
            }
            check(facts.format.isEmpty() || value.kind == PrimitiveKind.STRING) { "$property states a format, but is not a string" }
            check(facts.pattern.isEmpty() || value.kind == PrimitiveKind.STRING) { "$property states a pattern, but is not a string" }
        }
        return buildJsonObject {
            valueSchema(value, property, prefix, components).forEach { (key, node) -> put(key, node) }
            facts?.description?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
            facts?.format?.takeIf { it.isNotEmpty() }?.let { put("format", it) }
            facts?.minLength?.takeIf { it >= 0 }?.let { put("minLength", it) }
            facts?.maxLength?.takeIf { it >= 0 }?.let { put("maxLength", it) }
            facts?.pattern?.takeIf { it.isNotEmpty() }?.let { put("pattern", it) }
        }
    }

    /**
     * The schema of one value. A nullable value is described by its non-null form: whether
     * it may be absent is its property's `required`, not its schema.
     */
    private fun valueSchema(
        descriptor: SerialDescriptor,
        property: String,
        prefix: String?,
        components: MutableMap<String, JsonElement>,
    ): JsonObject {
        val value = if (descriptor.isNullable) descriptor.nonNullOriginal else descriptor
        return when (value.kind) {
            PrimitiveKind.STRING -> buildJsonObject { put("type", "string") }
            PrimitiveKind.INT ->
                buildJsonObject {
                    put("type", "integer")
                    put("format", "int32")
                }
            PrimitiveKind.BOOLEAN -> buildJsonObject { put("type", "boolean") }
            SerialKind.ENUM ->
                buildJsonObject {
                    put("type", "string")
                    putJsonArray("enum") { (0 until value.elementsCount).forEach { add(value.getElementName(it)) } }
                }
            StructureKind.LIST -> {
                val item = value.getElementDescriptor(0)
                check(!item.isNullable) { "OpenAPI schemas do not support nullable list items; $property has them" }
                buildJsonObject {
                    put("type", "array")
                    put("items", valueSchema(item, "$property[]", prefix, components))
                }
            }
            StructureKind.CLASS -> {
                offerings.nestedSchema(value, prefix)?.let { schema ->
                    schema.definitions.forEach { (name, node) ->
                        own(name, OfferingsSchemas.OWNER)
                        components[name] = node
                    }
                    return schema.node.jsonObject
                }
                val name = prefix.orEmpty() + value.serialName.substringAfterLast('.')
                define(name, value, prefix, components)
                reference(name)
            }
            PolymorphicKind.SEALED -> {
                val name = prefix.orEmpty() + value.serialName.substringAfterLast('.')
                define(name, value, prefix, components)
                reference(name)
            }
            else -> error("OpenAPI schemas do not support ${value.kind} values yet; $property is one")
        }
    }
}

/**
 * The schemas of commerce-runtime's Offerings bodies, exactly as the runtime's own
 * `offeringsOpenApiRenderer` renders them: it alone knows that `OfferingPriceDto` is a
 * `kind`-discriminated `oneOf` of three price forms, which no descriptor or example can say.
 * Fiona never restates an Offerings schema.
 *
 * `offeringsOpenApiRenderer` builds schemas through http4k's reflective schema generator,
 * which fails on kotlinx.serialization's JSON (as `ApiRenderer.Auto` does, see
 * [KotlinxSchemas]), so it renders with http4k's Jackson, and each schema joins this
 * document as the JSON it is. Jackson only renders these schemas: it never reads or writes a
 * request or response.
 */
private class OfferingsSchemas {
    private val renderer = offeringsOpenApiRenderer(Jackson)

    /** Whether [obj] is a body of commerce-runtime's Offerings routes. */
    fun describes(obj: Any) = obj.javaClass.packageName == OFFERINGS_PACKAGE

    /** Offering options nested in Fiona's form keep the same runtime-owned schemas as catalog responses. */
    fun nestedSchema(
        descriptor: SerialDescriptor,
        prefix: String?,
    ): JsonSchema<JsonElement>? =
        if (descriptor.serialName == OfferingDto.serializer().descriptor.serialName) {
            // The reflective runtime renderer needs a complete example to discover nullable fields.
            toSchema(
                OfferingDto(
                    "vanilla",
                    "soft-serve-flavor",
                    "Vanilla",
                    "Soft serve",
                    OfferingPriceDto("FIXED", "1.00", "USD"),
                    selectionState = OfferingSelectionStateDto.ENABLED,
                    availability = OfferingAvailabilityDto.AVAILABLE,
                ),
                null,
                prefix,
            )
        } else {
            null
        }

    fun toSchema(
        obj: Any,
        overrideDefinitionId: String?,
        refModelNamePrefix: String?,
    ): JsonSchema<JsonElement> {
        val schema = renderer.toSchema(obj, overrideDefinitionId, refModelNamePrefix)
        return JsonSchema(schema.node.toJsonElement(), schema.definitions.mapValues { (_, node) -> node.toJsonElement() })
    }

    private fun JsonNode.toJsonElement(): JsonElement = CommerceJson.parse(Jackson.compact(this))

    companion object {
        const val OWNER = "commerce-runtime's Offerings capability"
        private val OFFERINGS_PACKAGE = OfferingsCatalogDto::class.java.packageName
    }
}
