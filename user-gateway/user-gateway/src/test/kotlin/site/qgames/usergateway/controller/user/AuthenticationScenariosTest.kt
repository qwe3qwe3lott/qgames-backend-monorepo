package site.qgames.usergateway.controller.user

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import site.qgames.common.web.header.QgamesHeaders
import site.qgames.usergateway.publicapi.dto.user.SetPasswordRequest
import site.qgames.usergateway.BaseIntegrationTest
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

class AuthenticationScenariosTest : BaseIntegrationTest() {

    fun interface TokenTransport {
        fun MockHttpServletRequestDsl.attach(token: String)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTokens")
    fun `protected endpoint rejects untrusted credentials without calling user-auth`(case: String, token: String?) {
        stubUserAuthGet("/api/users/me", user())

        mockMvc.get("/api/users/me") {
            token?.let { cookie(Cookie("access_token", it)) }
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }

        userAuth.verify(0, getRequestedFor(urlEqualTo("/api/users/me")))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tokenTransports")
    fun `authenticated request reaches user-auth with user id taken from the token`(case: String, transport: TokenTransport) {
        stubUserAuthGet("/api/users/me", user())

        mockMvc.get("/api/users/me") {
            with(transport) { attach(accessToken()) }
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(USER_ID.toString()) }
        }

        userAuth.verify(
            getRequestedFor(urlEqualTo("/api/users/me")).withHeader(QgamesHeaders.USER_ID, equalTo(USER_ID.toString())),
        )
    }

    @Test
    fun `adding a login method forwards body and user id`() {
        stubUserAuthPost("/api/users/me/login-methods/password", 200, user())
        val request = SetPasswordRequest("new-password-1")

        mockMvc.post("/api/users/me/login-methods/password") {
            cookie(Cookie("access_token", accessToken()))
            contentType = MediaType.APPLICATION_JSON
            content = json(request)
        }.andExpect { status { isOk() } }

        userAuth.verify(
            postRequestedFor(urlEqualTo("/api/users/me/login-methods/password"))
                .withHeader(QgamesHeaders.USER_ID, equalTo(USER_ID.toString()))
                .withRequestBody(equalToJson(json(request))),
        )
    }

    companion object {
        @JvmStatic
        fun rejectedTokens(): Stream<Arguments> = Stream.of(
            arguments("no token", null),
            arguments("not a jwt", "garbage"),
            arguments("expired token", accessToken(expiresAt = Instant.now().minusSeconds(60))),
            arguments("token signed by unknown key", accessToken(signedByForeignKey = true)),
            arguments("token from another issuer", accessToken(issuer = "someone-else")),
        )

        @JvmStatic
        fun tokenTransports(): Stream<Arguments> = Stream.of(
            arguments("token in http-only cookie", TokenTransport { cookie(Cookie("access_token", it)) }),
            arguments("token in Authorization header", TokenTransport { header(HttpHeaders.AUTHORIZATION, "Bearer $it") }),
            arguments(
                "client tries to spoof X-User-Id",
                TokenTransport {
                    cookie(Cookie("access_token", it))
                    header(QgamesHeaders.USER_ID, UUID.randomUUID().toString())
                },
            ),
        )
    }
}
