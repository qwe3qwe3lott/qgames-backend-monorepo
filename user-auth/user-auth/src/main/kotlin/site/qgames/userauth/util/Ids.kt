package site.qgames.userauth.util

import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/** Id новых строк — UUID v7 (упорядочен по времени создания), генерирует приложение, а не БД. */
@OptIn(ExperimentalUuidApi::class)
fun newId(): UUID = Uuid.generateV7().toJavaUuid()
