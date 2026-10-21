package site.qgames.adminauth.exception.admin

import site.qgames.common.exception.exception.NotFoundException

/** Каталог ошибок администраторов. Коды — часть контракта с фронтом админки. */
object AdminErrors {

    fun adminNotFound() =
        NotFoundException("ADMIN_NOT_FOUND", "Admin not found")
}
