# Dev-only ключи

ES256-ключи для подписи JWT при локальном запуске (docker-compose и запуск из IDE). **Не использовать нигде, кроме локальной разработки**: в любом общем окружении ключи берутся из секретов.

| Пара | Сервис | kid |
|---|---|---|
| `jwt-private.pem` / `jwt-public.pem` | user-auth | `dev-key-1` |
| `admin-jwt-private.pem` / `admin-jwt-public.pem` | admin-auth | `admin-dev-key-1` |

У admin-auth своя пара: токен игрока не должен проходить проверку в admin-gateway.

Как сгенерировать новую пару для user-auth:

```bash
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out jwt-private.pem
openssl ec -in jwt-private.pem -pubout -out jwt-public.pem
```

Для admin-auth — то же самое с другими именами файлов:

```bash
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out admin-jwt-private.pem
openssl ec -in admin-jwt-private.pem -pubout -out admin-jwt-public.pem
```
