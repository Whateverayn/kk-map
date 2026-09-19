import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Android (:app, 将来の :car) から使うので, バイトコードは Android モジュールと同じ Java 11 に合わせる
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    testImplementation(libs.junit)
}
