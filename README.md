# qgames-backend

Бэкенд игрового портала qgames: модульный монолит (Spring Modulith) на Kotlin и Spring Boot 4.

| Модуль | Назначение |
|---|---|
| `user-auth` | аккаунты игроков, вход через Google и по паролю из профиля, JWT игроков |
| `admin-auth` | администраторы, вход по логину и паролю, JWT администраторов |
| `game` | игровые комнаты и партии всех игр (правила игр — модули `games/*`) |
| `app` | приложение: собирает модули, Security, CORS, OpenAPI, конфигурация, миграции |

Одно приложение на порту 8080: API портала — `/api/**`, API админки — `/api/admin/**`.

## Быстрый старт

Нужны Docker и JDK 25 или новее (байткод собирается под Java 25).

```bash
docker compose up --build -d --wait
```

- Swagger: http://localhost:8080/swagger-ui.html (группы `portal` и `admin`)
- OpenAPI: http://localhost:8080/v3/api-docs/portal, http://localhost:8080/v3/api-docs/admin
- Первый администратор на локальном стенде — `admin` / `admin`

Сборка и тесты (интеграционные, используют Testcontainers — нужен запущенный Docker):

```bash
./gradlew build
```

Правила разработки и архитектурные решения — в [CLAUDE.md](CLAUDE.md).
