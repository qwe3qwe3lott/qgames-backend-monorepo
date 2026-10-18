# Dev-only ключи

ES256-ключи для подписи JWT при локальном запуске (docker-compose и запуск из IDE). **Не использовать нигде, кроме локальной разработки**: в любом общем окружении ключи берутся из секретов.

Как сгенерировать новую пару:

```bash
openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out jwt-private.pem
openssl ec -in jwt-private.pem -pubout -out jwt-public.pem
```
