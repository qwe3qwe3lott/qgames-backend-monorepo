# qgames-backend

Бэкенд игрового портала qgames: микросервисы на Kotlin и Spring Boot 4.

| Сервис | Порт | Назначение |
|---|---|---|
| user-gateway | 8080 | публичный API, куки и JWT, проксирование |
| user-auth | 8081 | аккаунты, вход по email+паролю и через Google, JWT |
| game | 8082 | игровые комнаты и партии всех игр (правила игр — модули `games/*`) |

## Быстрый старт

Нужны Docker и JDK 25.

```bash
docker compose up --build -d --wait
```

- Swagger gateway: http://localhost:8080/swagger-ui.html (сервисы — порты 8081–8082)
- Письма с кодами подтверждения: http://localhost:8025 (Mailpit)

Сборка и тесты (интеграционные, используют Testcontainers — нужен запущенный Docker):

```bash
./gradlew build
```

Правила разработки и архитектурные решения — в [CLAUDE.md](CLAUDE.md).
