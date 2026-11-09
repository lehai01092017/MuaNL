plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "vn.nguyenlieu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.nguyenlieu.app"
        minSdk = 26
        targetSdk = 35
        // Mỗi lần build trên GitHub tăng số phiên bản -> Android nhận là bản cập nhật
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = 100 + run
        versionName = "1.$run"
    }

    // Khoá ký CỐ ĐỊNH: mọi bản build ký cùng một khoá -> cài đè được, KHÔNG mất dữ liệu.
    // Đừng xoá hay thay file keystore/muanl.jks, nếu không sẽ phải gỡ app để cài lại.
    signingConfigs {
        create("muanl") {
            storeFile = rootProject.file("keystore/muanl.jks")
            storePassword = "MuaNL2026"
            keyAlias = "muanl"
            keyPassword = "MuaNL2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("muanl")
        }
        debug {
            signingConfig = signingConfigs.getByName("muanl")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
