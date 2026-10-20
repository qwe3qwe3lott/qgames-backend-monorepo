package site.qgames.admingateway.controller.admin

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockHttpServletRequestDsl
import org.springframework.test.web.servlet.get
import site.qgames.adminauth.gatewayapi.dto.admin.AdminListResponse
import site.qgames.admingateway.BaseIntegrationTest
import site.qgames.common.web.header.QgamesHeaders
import java.time.Instant
import java.util.UUID
import java.util.stream.Stream

class AuthenticationScenariosTest : BaseIntegrationTest() {

    fun interface Credentials {
        fun MockHttpServletRequestDsl.attach()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedCredentials")
    fun `protected endpoint rejects untrusted credentials without calling admin-auth`(case: String, credentials: Credentials) {
        stubAdminAuthGet("/api/admins/me", 200, admin())

        mockMvc.get("/api/admins/me") {
            with(credentials) { attach() }
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }

        adminAuth.verify(0, getRequestedFor(urlEqualTo("/api/admins/me")))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trustedCredentials")
    fun `authenticated request reaches admin-auth with admin id taken from the token`(case: String, credentials: Credentials) {
        stubAdminAuthGet("/api/admins/me", 200, admin())

        mockMvc.get("/api/admins/me") {
            with(credentials) { attach() }
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(ADMIN_ID.toString()) }
            jsonPath("$.login") { value("root") }
        }

        adminAuth.verify(
            getRequestedFor(urlEqualTo("/api/admins/me")).withHeader(QgamesHeaders.USER_ID, equalTo(ADMIN_ID.toString())),
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("protectedEndpoints")
    fun `protected endpoint requires a session`(path: String) {
        mockMvc.get(path).andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("UNAUTHENTICATED") }
        }
    }

    @Test
    fun `admin list is proxied in admin-auth order`() {
        val second = admin(id = UUID.fromString("0199f0a0-0000-7000-8000-0000000000a2"), login = "operator")
        stubAdminAuthGet("/api/admins", 200, AdminListResponse(listOf(second, admin())))

        val result = mockMvc.get("/api/admins") {
            cookie(Cookie(ACCESS_COOKIE, accessToken()))
        }.andExpect {
            status { isOk() }
            jsonPath("$.admins.length()") { value(2) }
            jsonPath("$.admins[0].login") { value("operator") }
            jsonPath("$.admins[1].id") { value(ADMIN_ID.toString()) }
            jsonPath("$.admins[1].createdAt") { value("2026-10-20T10:00:00Z") }
        }

        assertThat(result.andReturn().response.contentAsString).doesNotContain("passwordHash")
        adminAuth.verify(getRequestedFor(urlEqualTo("/api/admins")).withHeader(QgamesHeaders.USER_ID, equalTo(ADMIN_ID.toString())))
    }

    @Test
    fun `admin-auth not found error is forwarded`() {
        stubAdminAuthGet("/api/admins/me", 404, problem(404, "ADMIN_NOT_FOUND"))

        mockMvc.get("/api/admins/me") {
            cookie(Cookie(ACCESS_COOKIE, accessToken()))
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("ADMIN_NOT_FOUND") }
        }
    }

    companion object {
        @JvmStatic
        fun rejectedCredentials(): Stream<Arguments> = Stream.of(
            arguments("no token", Credentials { }),
            arguments("not a jwt", Credentials { cookie(Cookie(ACCESS_COOKIE, "garbage")) }),
            arguments("expired token", Credentials { cookie(Cookie(ACCESS_COOKIE, accessToken(expiresAt = Instant.now().minusSeconds(120)))) }),
            arguments("token signed by unknown key", Credentials { cookie(Cookie(ACCESS_COOKIE, accessToken(signedByForeignKey = true))) }),
            arguments(
                "player token from user-auth issuer",
                Credentials { cookie(Cookie(ACCESS_COOKIE, accessToken(issuer = "qgames-user-auth"))) },
            ),
            arguments(
                "player token in Authorization header",
                Credentials { header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken(issuer = "qgames-user-auth")}") },
            ),
            // Кука портала admin-gateway не читает, даже если токен в ней валиден для админки.
            arguments("valid token in the portal cookie access_token", Credentials { cookie(Cookie("access_token", accessToken())) }),
            arguments("forged X-User-Id without token", Credentials { header(QgamesHeaders.USER_ID, ADMIN_ID.toString()) }),
        )

        @JvmStatic
        fun trustedCredentials(): Stream<Arguments> = Stream.of(
            arguments("token in http-only cookie", Credentials { cookie(Cookie(ACCESS_COOKIE, accessToken())) }),
            arguments("token in Authorization header", Credentials { header(HttpHeaders.AUTHORIZATION, "Bearer ${accessToken()}") }),
            arguments(
                "client tries to spoof X-User-Id",
                Credentials {
                    cookie(Cookie(ACCESS_COOKIE, accessToken()))
                    header(QgamesHeaders.USER_ID, UUID.randomUUID().toString())
                },
            ),
        )

        @JvmStatic
        fun protectedEndpoints(): Stream<Arguments> = Stream.of(arguments("/api/admins"), arguments("/api/admins/me"))
    }
}
