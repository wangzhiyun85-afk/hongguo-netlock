import java.util.Properties

plugins {
    id("com.android.application")
}

// 签名信息不写进仓库（仓库是公开的）：统一放在根目录 local.properties（已被 .gitignore 忽略）
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.jingxuan.netlock"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jingxuan.netlock"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "2.8.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file(localProps.getProperty("RELEASE_STORE_FILE") ?: "../apk/jingxuan.keystore")
            storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
