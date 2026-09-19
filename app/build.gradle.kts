plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.imlec.yardimci"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.imlec.yardimci"
        minSdk = 26
        targetSdk = 34
        // CI'da her build artar (Actions run numarası); yerelde 1
        val runNo = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNo
        versionName = "1.0.$runNo"
    }

    // Imza bilgileri repoda tutulmaz: CI, GitHub Secrets'tan KEYSTORE_* ortam degiskenlerini verir.
    val ksPath = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (ksPath != null) {
            create("personal") {
                val pw = System.getenv("KEYSTORE_PASSWORD")
                storeFile = file(ksPath)
                storePassword = pw
                keyAlias = System.getenv("KEY_ALIAS") ?: "personal"
                keyPassword = System.getenv("KEY_PASSWORD") ?: pw
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("personal") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
}
