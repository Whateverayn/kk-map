plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.whateverayn.kkmap.core.map"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 30

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    // MapView / MapLibreMap を公開 API に含めるため api
    api(libs.maplibre.android.sdk)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 動作確認用 MapDebugActivity(debug ビルドのみ)
    debugImplementation(libs.androidx.activity.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
