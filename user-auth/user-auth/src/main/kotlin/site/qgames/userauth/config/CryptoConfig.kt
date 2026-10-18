package site.qgames.userauth.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.SecurityContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.Resource
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.security.KeyFactory
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Configuration
class CryptoConfig {

    /** Argon2id: соль генерируется на каждый пароль и хранится внутри хеша. */
    @Bean
    fun passwordEncoder(): PasswordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()

    /**
     * Ключ подписи access-токенов. Читается из конфигурации, а не генерируется при старте,
     * чтобы все инстансы подписывали одним ключом.
     */
    @Bean
    fun jwtSigningKey(properties: AuthProperties): ECKey {
        val keyFactory = KeyFactory.getInstance("EC")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(readPem(properties.jwt.privateKey))) as ECPrivateKey
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(readPem(properties.jwt.publicKey))) as ECPublicKey
        return ECKey.Builder(Curve.P_256, publicKey)
            .privateKey(privateKey)
            .keyID(properties.jwt.keyId)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.ES256)
            .build()
    }

    @Bean
    fun jwtEncoder(jwtSigningKey: ECKey): JwtEncoder =
        NimbusJwtEncoder(ImmutableJWKSet<SecurityContext>(JWKSet(jwtSigningKey)))

    private fun readPem(resource: Resource): ByteArray {
        val body = resource.inputStream.use { it.readAllBytes().decodeToString() }
            .lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString("")
        return Base64.getMimeDecoder().decode(body)
    }
}
