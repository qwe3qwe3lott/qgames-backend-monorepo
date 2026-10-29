plugins {
    id("qgames.spring-module")
}

dependencies {
    implementation(project(":libs:common-exception"))
    implementation(project(":libs:common-web"))
    // События-патчи потоков партии и комнаты: модель операции и общий применитель.
    implementation(project(":libs:doc-patch"))
    api(project(":game:game-api"))
    // Событие профиля (UserProfileChanged) и описание таблицы user для чтения профилей — из публичного контракта
    // user-auth, не из его реализации.
    implementation(project(":user-auth:user-auth-api"))
    // Правила игр. Новая игра — новый модуль games/* здесь и его бин в GamesConfig.
    implementation(project(":games:nothing-game"))
    implementation(project(":games:checkers-game"))

    // PGConnection: LISTEN шины событий комнат и партий (RoomEventListener).
    implementation("org.postgresql:postgresql")
    // Кеш состояний партий по проекциям зрителей (CaffeineSessionStateCache).
    implementation("com.github.ben-manes.caffeine:caffeine")
}
