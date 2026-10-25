package site.qgames.userauth.mapper.auth

import org.jetbrains.exposed.v1.core.ResultRow
import org.springframework.stereotype.Component
import site.qgames.userauth.model.auth.RefreshTokenModel
import site.qgames.userauth.table.auth.RefreshTokenTable

/** Строки таблицы `user_refresh_token` → модели. */
@Component
class AuthRowMapper {

    fun toRefreshToken(row: ResultRow) = RefreshTokenModel(
        id = row[RefreshTokenTable.id],
        userId = row[RefreshTokenTable.userId],
        familyId = row[RefreshTokenTable.familyId],
        tokenHash = row[RefreshTokenTable.tokenHash],
        expiresAt = row[RefreshTokenTable.expiresAt],
        revokedAt = row[RefreshTokenTable.revokedAt],
        rotatedAt = row[RefreshTokenTable.rotatedAt],
        createdAt = row[RefreshTokenTable.createdAt],
    )
}
