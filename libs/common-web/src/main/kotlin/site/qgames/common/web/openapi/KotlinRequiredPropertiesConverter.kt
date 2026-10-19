package site.qgames.common.web.openapi

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.media.Schema
import kotlin.reflect.full.primaryConstructor

/**
 * Помечает `required` свойства Kotlin-DTO: параметры главного конструктора, которые не nullable и не имеют значения
 * по умолчанию. Так генераторы клиентов (Orval) получают обязательные поля без `@Schema(requiredMode)` на каждом поле.
 * Nullable и необязательные поля springdoc описывает сам (`["string", "null"]`).
 */
class KotlinRequiredPropertiesConverter : ModelConverter {

    override fun resolve(type: AnnotatedType, context: ModelConverterContext, chain: Iterator<ModelConverter>): Schema<*>? {
        val schema = if (chain.hasNext()) chain.next().resolve(type, context, chain) else null
        val rawClass = Json.mapper().constructType(type.type).rawClass
        if (schema == null || rawClass.isEnum || !rawClass.isAnnotationPresent(Metadata::class.java)) {
            return schema
        }
        // Для классов springdoc обычно возвращает $ref, а саму схему кладёт в контекст.
        val model = schema.`$ref`?.let { context.definedModels[it.substringAfterLast('/')] } ?: schema
        val properties = model.properties ?: return schema
        val constructor = runCatching { rawClass.kotlin.primaryConstructor }.getOrNull() ?: return schema
        constructor.parameters
            .filter { !it.isOptional && !it.type.isMarkedNullable }
            .mapNotNull { it.name }
            .filter { it in properties && model.required?.contains(it) != true }
            .forEach(model::addRequiredItem)
        return schema
    }
}
