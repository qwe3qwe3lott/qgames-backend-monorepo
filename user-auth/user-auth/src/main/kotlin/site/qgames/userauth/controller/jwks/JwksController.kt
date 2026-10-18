package site.qgames.userauth.controller.jwks

import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Публичные ключи подписи access-токенов. Gateway кеширует их и проверяет JWT локально.
 * Для ротации ключей сюда добавляется новый ключ с другим kid, старый удаляется после истечения его токенов.
 */
@Tag(name = "Ключи подписи", description = "Публичные ключи, которыми проверяются access-токены")
@RestController
class JwksController(jwtSigningKey: ECKey) {

    private val jwkSet: Map<String, Any> = JWKSet(jwtSigningKey.toPublicJWK()).toJSONObject()

    @Operation(
        summary = "Публичные ключи JWT (JWKS)",
        description = "Набор публичных ключей ES256, которыми подписываются access-токены. Gateway кеширует его и " +
            "проверяет токены локально; при ротации ключей здесь появляется ключ с новым kid.",
    )
    @ApiResponse(responseCode = "200", description = "JWK Set с публичными ключами")
    @GetMapping("/.well-known/jwks.json")
    fun jwks(): Map<String, Any> = jwkSet
}
