package site.qgames.game.config

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import javax.sql.DataSource

/**
 * Шедулеры работают на всех инстансах. Задачи, которые должен выполнять один инстанс (EmptyRoomCleanupJob,
 * ForceDisabledGameCleanupJob), берут ShedLock (таблица shedlock); MoveTimeoutJob и RoomLeaveJob делят работу между инстансами через FOR UPDATE SKIP LOCKED. Запуск по расписанию
 * у каждого джоба включается своим флагом (`…-enabled`), в тестах джобы вызываются напрямую.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
class SchedulingConfig {

    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build(),
        )
}
