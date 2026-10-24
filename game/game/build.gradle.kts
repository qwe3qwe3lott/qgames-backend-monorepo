plugins {
    id("qgames.spring-module")
}

dependencies {
    implementation(project(":libs:common-exception"))
    implementation(project(":libs:common-web"))
    api(project(":game:game-api"))
    // Событие профиля (UserProfileChanged) — из публичного контракта user-auth, не из его реализации.
    implementation(project(":user-auth:user-auth-api"))
    // Правила игр. Новая игра — новый модуль games/* здесь и его бин в GamesConfig.
    implementation(project(":games:nothing-game"))
    implementation(project(":games:checkers-game"))

    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    // PGConnection: LISTEN шины событий комнат и партий (RoomEventListener).
    implementation("org.postgresql:postgresql")
    // Кеш состояний партий по проекциям зрителей (CaffeineSessionStateCache).
    implementation("com.github.ben-manes.caffeine:caffeine")
}
