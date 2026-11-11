plugins {
    java
}

group = "co.agrocraft"
version = "0.6.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // El servidor descarga Paho al arrancar (sección `libraries` de plugin.yml); aquí solo se compila contra él.
    compileOnly("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    // Marcadores en el mapa web; el servidor ya trae squaremap 1.3.12 (dependencia opcional)
    compileOnly("xyz.jpenilla:squaremap-api:1.3.12")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.jar {
    archiveBaseName.set("agrocraft-bridge")
}
