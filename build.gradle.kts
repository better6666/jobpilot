plugins {
    java
    id("org.springframework.boot") version "3.5.16"
}

group = "com.jobpilot"
version = "0.1.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
}

dependencies {
    // 用 Spring Boot 官方 BOM 管理版本
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.baomidou:mybatis-plus-spring-boot3-starter:3.5.9")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // Lombok：compileOnly 管编译期可见性，annotationProcessor 管代码生成，缺一不可
    val lombok = "org.projectlombok:lombok:1.18.42"
    compileOnly(lombok)
    annotationProcessor(lombok)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation"))
}

springBoot {
    buildInfo()
}
