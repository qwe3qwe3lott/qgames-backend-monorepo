# qgames-backend

Бэкенд игрового портала qgames: микросервисы на Kotlin и Spring Boot 4.

| Сервис | Порт | Назначение |
|---|---|---|
| user-gateway | 8080 | публичный API, куки и JWT, проксирование |
| user-auth | 8081 | аккаунты, вход через Google и по паролю из профиля, JWT |
| game | 8082 | игровые комнаты и партии всех игр (правила игр — модули `games/*`) |
| admin-gateway | 8083 | API админ-панели, куки и JWT администраторов, проксирование |
| admin-auth | 8084 | администраторы, вход по логину и паролю, JWT администраторов |

## Быстрый старт

Нужны Docker и JDK 25 или новее (байткод собирается под Java 25).

```bash
docker compose up --build -d --wait
```

- Swagger gateway: http://localhost:8080/swagger-ui.html (сервисы — порты 8081–8082)
- Swagger admin-gateway: http://localhost:8083/swagger-ui.html (admin-auth — порт 8084). Первый администратор на локальном стенде — `admin` / `admin`

Сборка и тесты (интеграционные, используют Testcontainers — нужен запущенный Docker):

```bash
./gradlew build
```

Правила разработки и архитектурные решения — в [CLAUDE.md](CLAUDE.md).
