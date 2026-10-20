package site.qgames.common.web.openapi

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer

/**
 * Полиморфные DTO (sealed-тип с `@JsonTypeInfo`/`@JsonSubTypes` и `@Schema(oneOf, discriminatorProperty)`) должны
 * давать в OpenAPI чистый union: у родителя — `oneOf` + `discriminator`, у вариантов — обычные объекты.
 * swagger-core дополнительно пишет в каждый вариант `allOf: [$ref родителя]`, и получается цикл
 * (родитель → вариант → родитель), из которого генераторы клиентов (Orval) строят неверные типы.
 * Здесь эта обратная ссылка убирается, а собственные свойства и `required` варианта поднимаются на его верхний уровень;
 * у родителя убирается дублирующее свойство-дискриминатор.
 */
class DiscriminatedUnionCustomizer : OpenApiCustomizer {

    override fun customise(openApi: OpenAPI) {
        val schemas = openApi.components?.schemas ?: return
        schemas.filterValues { it.discriminator != null && !it.oneOf.isNullOrEmpty() }.forEach { (name, union) ->
            val unionRef = SCHEMA_REF_PREFIX + name
            // Свойство-дискриминатор, которое swagger-core дописывает родителю, уже есть в каждом варианте
            // (с конкретным значением): у union оно лишнее и только расширяет тип до string.
            val discriminator = union.discriminator.propertyName
            if (union.properties.orEmpty().keys.all { it == discriminator }) {
                union.properties = null
                union.required = union.required?.filter { it != discriminator }?.takeIf { it.isNotEmpty() }
            }
            union.oneOf
                .mapNotNull { variant -> variant.`$ref`?.removePrefix(SCHEMA_REF_PREFIX)?.let(schemas::get) }
                .forEach { variant -> detach(variant, unionRef) }
        }
    }

    private fun detach(variant: Schema<*>, unionRef: String) {
        val allOf = variant.allOf ?: return
        if (allOf.none { it.`$ref` == unionRef }) return
        val own = allOf.filter { it.`$ref` != unionRef }
        if (own.any { it.`$ref` != null }) {
            // Вариант наследует ещё что-то — оставляем allOf без ссылки на union.
            variant.allOf = own
            return
        }
        variant.allOf = null
        // OpenAPI 3.1: тип задаётся через types, type(...) в итоговый JSON не попадает.
        variant.types = setOf("object")
        own.forEach { part ->
            part.properties?.forEach { (property, schema) -> variant.addProperty(property, schema) }
            part.required?.filter { variant.required?.contains(it) != true }?.forEach(variant::addRequiredItem)
        }
    }

    companion object {
        private const val SCHEMA_REF_PREFIX = "#/components/schemas/"
    }
}
