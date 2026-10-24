package site.qgames

import com.tngtech.archunit.core.domain.JavaClass
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

/**
 * Границы модулей Spring Modulith: модули (`userauth`, `adminauth`, `game`, `app`) обращаются друг к другу только
 * через именованный интерфейс `api` (пакеты `site.qgames.<модуль>.api`, модули `<m>-api`), циклов нет.
 * Инфраструктура `site.qgames.common` и правила игр `site.qgames.games` модулями не считаются.
 */
class ModularityTest {

    private val modules = ApplicationModules.of(
        QgamesApplication::class.java,
        JavaClass.Predicates.resideInAnyPackage("site.qgames.common..", "site.qgames.games.."),
    )

    @Test
    fun `module boundaries are respected`() {
        modules.verify()
    }

    @Test
    fun `application consists of the expected modules`() {
        assertThat(modules.map { it.identifier.toString() }).containsExactlyInAnyOrder("app", "userauth", "adminauth", "game")
    }
}
