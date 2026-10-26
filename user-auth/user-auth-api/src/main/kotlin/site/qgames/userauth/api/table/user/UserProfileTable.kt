package site.qgames.userauth.api.table.user

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * Публичный профиль из таблицы `user` модуля user-auth — только для чтения другими модулями (join по id пользователя).
 * Подмножество колонок: логин и прочие детали аккаунта наружу не выходят. Пишет в таблицу только user-auth, у него
 * своё полное описание таблицы. Аватар — имя enum [site.qgames.userauth.api.dto.user.Avatar].
 */
object UserProfileTable : Table("user") {
    val id = javaUUID("id")
    val nickname = varchar("nickname", 32)
    val avatar = varchar("avatar", 16)

    override val primaryKey = PrimaryKey(id)
}
