package site.qgames.common.web.openapi

import io.swagger.v3.oas.models.OpenAPI
import org.springdoc.core.customizers.GlobalOpenApiCustomizer

/**
 * Теги документа — по имени. `springdoc.writer-with-order-by-keys` сортирует только ключи объектов, а не массив
 * `tags`; без стабильного порядка сгенерированный клиент фронта (Orval, `tags-split`) мог бы меняться без изменения
 * контракта.
 *
 * [GlobalOpenApiCustomizer]: применяется ко всем группам OpenAPI (portal, admin).
 */
class SortedTagsCustomizer : GlobalOpenApiCustomizer {

    override fun customise(openApi: OpenAPI) {
        openApi.tags = openApi.tags?.sortedBy { it.name }
    }
}
