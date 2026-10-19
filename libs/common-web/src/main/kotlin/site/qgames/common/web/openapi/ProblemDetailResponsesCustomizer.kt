package site.qgames.common.web.openapi

import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.MediaType
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.http.ProblemDetail

/**
 * Все ошибки (4xx/5xx) отдаются как ProblemDetail с полем `code`. Чтобы не повторять тело ответа в каждой
 * аннотации `@ApiResponse`, схема подставляется здесь; в аннотации остаются только статус и описание.
 */
class ProblemDetailResponsesCustomizer : OpenApiCustomizer {

    override fun customise(openApi: OpenAPI) {
        val components = openApi.components ?: Components().also { openApi.components = it }
        ModelConverters.getInstance().readAll(ProblemDetail::class.java).forEach(components::addSchemas)
        val problemSchema = components.schemas?.get(PROBLEM_DETAIL) ?: return
        // OpenAPI 3.1: тип задаётся через types, type(...) в итоговый JSON не попадает.
        problemSchema.addProperty("code", Schema<String>().types(setOf("string")).description("Машиночитаемый код ошибки"))
        problemSchema.addProperty(
            "errors",
            Schema<Any>().types(setOf("object"))
                .additionalProperties(Schema<String>().types(setOf("string")))
                .description("Ошибки по полям запроса (у VALIDATION_ERROR): имя поля → сообщение"),
        )
        problemSchema.addProperty(
            "reason",
            Schema<String>().types(setOf("string")).description("Причина отказа правил игры (у MOVE_REJECTED), например NOT_YOUR_TURN"),
        )

        val problemContent = Content().addMediaType(
            PROBLEM_JSON,
            MediaType().schema(Schema<Any>().`$ref`("#/components/schemas/$PROBLEM_DETAIL")),
        )
        openApi.paths.orEmpty().values
            .flatMap { it.readOperations() }
            .forEach { operation ->
                operation.responses.orEmpty()
                    .filterKeys { it.startsWith("4") || it.startsWith("5") }
                    .values
                    .forEach { it.content = problemContent }
            }
    }

    companion object {
        private const val PROBLEM_DETAIL = "ProblemDetail"
        private const val PROBLEM_JSON = org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE
    }
}
