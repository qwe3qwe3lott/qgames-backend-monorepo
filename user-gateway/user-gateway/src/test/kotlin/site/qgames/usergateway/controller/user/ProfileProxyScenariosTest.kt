package site.qgames.usergateway.controller.user

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.patch
import com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import jakarta.servlet.http.Cookie
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.patch
import site.qgames.common.web.header.QgamesHeaders
import site.qgames.usergateway.BaseIntegrationTest

class ProfileProxyScenariosTest : BaseIntegrationTest() {

    @ParameterizedTest(name = "body {0}")
    @ValueSource(strings = ["""{"nickname":"Renamed"}""", """{"avatar":"DOG"}""", """{"nickname":"Renamed","avatar":"DOG"}"""])
    fun `profile update is proxied to user-auth with the user id`(body: String) {
        userAuth.stubFor(patch(urlEqualTo("/api/users/me/profile")).willReturn(okJson(json(user()))))

        mockMvc.patch("/api/users/me/profile") {
            cookie(Cookie("access_token", accessToken()))
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect {
            status { isOk() }
            jsonPath("$.nickname") { value("Player") }
            jsonPath("$.avatar") { value("CAT") }
        }

        userAuth.verify(
            patchRequestedFor(urlEqualTo("/api/users/me/profile"))
                .withHeader(QgamesHeaders.USER_ID, equalTo(USER_ID.toString()))
                // Непереданные поля gateway шлёт как null — для user-auth это то же «не менять».
                .withRequestBody(equalToJson(body, true, true)),
        )
    }
}
