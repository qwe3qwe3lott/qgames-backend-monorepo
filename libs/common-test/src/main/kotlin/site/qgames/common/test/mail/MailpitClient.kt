package site.qgames.common.test.mail

import org.springframework.web.client.RestClient
import org.springframework.web.client.body

class MailpitClient(baseUrl: String) {

    private val restClient = RestClient.create(baseUrl)

    fun deleteAll() {
        restClient.delete().uri("/api/v1/messages").retrieve().toBodilessEntity()
    }

    /** Тексты писем на адрес, от новых к старым. */
    fun messagesTo(email: String): List<String> {
        val search = restClient.get()
            .uri("/api/v1/search?query={query}", "to:\"$email\"")
            .retrieve()
            .body<Map<String, Any?>>()
        @Suppress("UNCHECKED_CAST")
        val messages = search?.get("messages") as? List<Map<String, Any?>> ?: return emptyList()
        return messages.map { message ->
            val details = restClient.get()
                .uri("/api/v1/message/{id}", message["ID"])
                .retrieve()
                .body<Map<String, Any?>>()
            details?.get("Text") as? String ?: ""
        }
    }
}
