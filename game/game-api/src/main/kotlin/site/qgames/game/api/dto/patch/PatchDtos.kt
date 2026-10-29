package site.qgames.game.api.dto.patch

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Общие части событий-патчей потоков партии и комнаты: метод, данные операции и путь. Событие — одна операция над
 * документом потока (партии `{status, meta, state}` или комнаты), клиент применяет его одним общим применителем.
 */
enum class PatchEventMethod {
    /** Заменить или создать значение по пути целиком. */
    PUT,

    /** Неглубокое слияние объекта по пути с value. */
    PATCH,

    /** Удалить поле объекта или элемент массива по id. */
    DELETE,

    /** Документ не меняется: данные для журнала. */
    INFO,
}

/** Методы строками: нужны в аннотациях Jackson и OpenAPI (дискриминатор method). */
object PatchEventMethods {
    const val PUT = "PUT"
    const val PATCH = "PATCH"
    const val DELETE = "DELETE"
    const val INFO = "INFO"
}

@Schema(description = "Данные PUT: путь и новое значение (любой JSON, в том числе null)")
data class PutData(
    @field:ArraySchema(schema = Schema(implementation = PathSegment::class), arraySchema = Schema(description = PATH_DESCRIPTION))
    val path: List<Any>,
    @field:Schema(description = "Новое значение: любой JSON, в том числе null. Схема — схема документа по пути")
    val value: Any?,
)

@Schema(description = "Данные PATCH: путь к объекту и поля для неглубокого слияния")
data class PatchData(
    @field:ArraySchema(schema = Schema(implementation = PathSegment::class), arraySchema = Schema(description = PATH_DESCRIPTION))
    val path: List<Any>,
    @field:Schema(description = "Поля, которые заменяют поля объекта по пути (вложенные значения — целиком, null — как значение)")
    val value: Map<String, Any?>,
)

@Schema(description = "Данные DELETE: путь к полю объекта или элементу массива")
data class DeleteData(
    @field:ArraySchema(schema = Schema(implementation = PathSegment::class), arraySchema = Schema(description = PATH_DESCRIPTION))
    val path: List<Any>,
)

/** Только схема OpenAPI: данные INFO — произвольный объект (в Kotlin — Map). */
@Schema(
    name = "InfoData",
    description = "Данные INFO: произвольный объект для журнала и логов, документ не меняет",
    types = ["object"],
    additionalProperties = Schema.AdditionalPropertiesValue.TRUE,
)
class InfoData

/** Только схема OpenAPI: сегмент пути — строка (ключ объекта) или `{id}` (элемент массива по id). */
@Schema(
    name = "PathSegment",
    description = "Сегмент пути: строка — ключ объекта, {id} — элемент массива с этим id (адрес не зависит от индекса)",
    oneOf = [String::class, PathSegmentId::class],
)
class PathSegment

/** Элемент массива по id. */
data class PathSegmentId(
    val id: String,
)

private const val PATH_DESCRIPTION = "Путь от корня документа потока: [] — весь документ. Партия: [\"state\", \"timer\"], " +
    "[\"state\", \"players\", {\"id\": \"…\"}, \"moveOptions\"]; комната: [\"members\", {\"id\": \"…\"}], [\"settings\"]"
