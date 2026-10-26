# Аутентификация и безопасность

Справочник к CLAUDE.md §5: читать, когда трогаешь `user-auth`, `admin-auth`, Security, куки, JWT или профиль игрока.

- **Две цепочки Security в app** (`SecurityConfig`):
  - админка — `/api/admin/**` (`securityMatcher`, `@Order(1)`): кука `admin_access_token` или `Authorization: Bearer`, токены admin-auth (свой ключ, issuer `qgames-admin-auth`); permitAll — `/api/admin/auth/**`;
  - портал — всё остальное (`@Order(2)`): кука `access_token` или `Authorization: Bearer`, токены user-auth (issuer `qgames-user-auth`); permitAll — `/api/auth/**`, `GET /api/games` и `/api/games/*`, Swagger (`/swagger-ui*`, `/v3/api-docs/**`), `/actuator/health/**`.
  - Токен игрока в админку не проходит, токен админа на портал — тоже (разные ключи и issuer). Без токена или с недействительным — 401 `UNAUTHENTICATED` (ProblemDetail). На эндпоинтах аутентификации access-токен не читается (`CookieBearerTokenResolver`): протухшая кука не ломает refresh.
  - JWT проверяется локально по **публичной части ключа подписи из конфигурации** (`qgames.security.{portal,admin}.public-key` → те же PEM, что у модулей), без JWKS-эндпоинта: `NimbusJwtDecoder` с `SingleKeyJWSKeySelector` (`withPublicKey` в Spring Security принимает только RSA). Проверяются подпись ES256, срок и issuer, `kid` не сверяется.
- `user-auth` подписывает access-JWT игроков (**ES256**, TTL 15 мин, `sub` = userId).
- **Refresh-токен:** случайная строка, в БД — только SHA-256; TTL 30 дней; ротация при каждом обновлении.
  - **Окно терпимости 10 с** (`qgames.auth.refresh-reuse-grace`). Повтор уже ротированного токена в течение окна, если в семье есть активный токен, выдаёт ещё одну пару той же сессии. Так обновляют сессию параллельные вкладки.
  - Повтор позже окна, после logout или после обнаружения кражи отзывает всё семейство (`family_id`) с ошибкой `REFRESH_TOKEN_REUSED`.
  - Ротацию от прочих отзывов отличает `rotated_at` (`user_refresh_token`, `admin_refresh_token`).
- **Сессия админа (`admin-auth`):**
  - свой ключ подписи ES256 и issuer `qgames-admin-auth`;
  - access — 5 мин, refresh — 15 мин. Refresh скользящий: каждая ротация даёт полный TTL. Окно терпимости 10 с (`qgames.admin-auth.refresh-reuse-grace`), ротация и отзыв семьи — как у user-auth;
  - логин сравнивается точно, без lowercase и trim. При неизвестном логине проверяется dummy-хеш, чтобы время ответа не выдавало существующие логины.
- **Первый админ:** регистрации нет. При старте приложения `AdminBootstrapService` создаёт админа из `ADMIN_BOOTSTRAP_LOGIN` / `ADMIN_BOOTSTRAP_PASSWORD`, только если таблица `admin` пуста.
  - Проверка «таблица пуста» и вставка — в одной пишущей транзакции (`AdminExposedRepository.insertIfNoAdmins`). Если админ создан, пишется лог WARN.
  - Значений по умолчанию в `application.yml` нет: без env бутстрап выключен, а если задано только одно из двух, приложение не стартует. docker-compose задаёт `admin` / `admin`.
- **Куки ставят и читают auth-контроллеры модулей** (`cookie/AuthCookies` в user-auth и admin-auth): `HttpOnly; Secure; SameSite=Lax`. Токены в теле ответов не отдаются.
  - Имена и пути — константы контракта `AuthCookieNames` в `<m>-api`: портал — `access_token` (`Path=/`) и `refresh_token` (`Path=/api/auth`); админка — `admin_access_token` (`Path=/api/admin`) и `admin_refresh_token` (`Path=/api/admin/auth`). Имена разные: оба фронта ходят в одно приложение, и одноимённые куки перетёрли бы друг друга. `secure`/`sameSite` — свойства модулей (`qgames.auth.cookies`, `qgames.admin-auth.cookies`, env `COOKIES_SECURE`).
  - refresh без куки или с отклонённым токеном (401) удаляет обе куки; logout отзывает сессию по куке и всегда удаляет обе куки; смена пароля выдаёт новую сессию в куках.
- **Same-origin, без CORS.** API портала — `/api/**` на хосте портала (`<домен>`), API админки — `/api/admin/**` на хосте админки (`admin.<домен>`); оба хоста проксируют в одно app (CLAUDE.md §1). CORS в app нет: в обеих цепочках он явно выключен (`cors { disable() }`), заголовков `Access-Control-*` app не отдаёт, и `Origin` запроса не проверяет. Чужая страница может отправить запрос, но не прочтёт ответ, а JSON-запросы ей не отправить вовсе: им нужен preflight, а он не проходит.
- **CSRF-токены не используются** (stateless, сессий нет). Защита держится на трёх вещах:
  - куки `SameSite=Lax`: с кросс-сайтовых POST/PUT/PATCH/DELETE и `fetch` браузер их не отправляет; GET-ручки данных не меняют, кроме подписки на поток комнаты (`GET /api/rooms/{roomId}/events` — это вход в комнату, присутствие = открытый поток);
  - тела запросов — только JSON: форма или `text/plain` получают 415, а JSON с другого origin требует preflight;
  - портал и админка — один сайт (`<домен>` и `admin.<домен>`), чужие сайты кук не получают. У DuckDNS `duckdns.org` — публичный суффикс, поэтому `<имя>.duckdns.org` — отдельный сайт, другие поддомены DuckDNS для него кросс-сайтовые.
  - Принятый риск: хосты портала и админки — один сайт, поэтому со страницы админки Lax-куки портала уходят, а POST без тела (старт, пауза, отмена партии, logout) — «простой» запрос без preflight и выполнится (ответ странице недоступен). Оба хоста наши, так что это опасно только при XSS в админке. Так же и с потоком комнаты: Lax-куки уходят при переходе верхнего уровня с любого сайта, а с хоста админки — и с `EventSource` (`withCredentials`), поэтому чужая ссылка или страница админки может «войти» пользователя в комнату, пока открыт поток (данных страница не увидит). Проверка `Sec-Fetch-Site`/`Sec-Fetch-Mode` на изменяющих ручках и SSE-потоках — решить при деплое.

  Перенос фронта на другой сайт (не поддомен) — пересмотр модели CSRF, только по согласованию.

- **Пароли:** Argon2id (`Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8`), соль внутри хеша.
- **Регистрации по email нет, писем user-auth не шлёт.** Аккаунт создаётся только входом через Google. Пароль добавляется в профиле (`POST /api/users/me/login-methods/password`), после этого можно входить по email аккаунта (это email Google-аккаунта, которым он создан) и паролю. Сброса пароля нет: забывший пароль входит через Google и меняет его.
- **Google:** фронт получает ID token через Google Identity Services и шлёт `POST /api/auth/google`; проверяем подпись по JWKS Google, `iss`, `aud` (`GOOGLE_CLIENT_IDS`).
- **Профиль игрока:**
  - ник (3–32 символа, без пробелов по краям, **не уникален** — игроки различаются по id) и аватар из enum `Avatar { CAT, DOG }`, набор аватаров определён на фронте;
  - новому Google-аккаунту генерируется ник `player-xxxxxx` и аватар `CAT`;
  - меняются через `PATCH /api/users/me/profile` (только переданные поля);
  - создание аккаунта и каждое изменение публикуются событием модуля `UserProfileChanged` (CLAUDE.md §4 «События модулей»); правки — «последняя запись побеждает».
- **Способы входа:** `user_identity(provider, subject)`, к одному аккаунту можно привязать несколько. Привязка **только явная** (из залогиненной сессии, `/api/users/me/login-methods/*`), автопривязки нет.
- **Вход через Google** (`POST /api/auth/google`): Google-аккаунт привязан (по `subject`) — сессия его владельца; иначе email не подтверждён в Google — 400 `GOOGLE_EMAIL_NOT_VERIFIED` (email — логин для входа по паролю); иначе аккаунт с таким email уже есть — 409 `ACCOUNT_LINK_REQUIRED` (защитный случай, email в БД уникален); иначе — новый аккаунт.
- Ключи подписи — из конфигурации/секретов, никогда не генерируются при старте. `docker/dev-keys` — только для локальной разработки: `jwt-*.pem` у user-auth (env `JWT_*`), `admin-jwt-*.pem` (kid `admin-dev-key-1`) у admin-auth (env `ADMIN_JWT_*`).
