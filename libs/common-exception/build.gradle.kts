plugins {
    id("qgames.kotlin-library")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    // Обработчик конфликтов БД подключается, только если у сервиса есть spring-tx (DataAccessException).
    compileOnly("org.springframework:spring-tx")
}
