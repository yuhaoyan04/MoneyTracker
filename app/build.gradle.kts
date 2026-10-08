import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.devtools.ksp)
}

android {
    namespace = "com.mudasir.smartledger"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mudasir.smartledger"
        minSdk = 24
        targetSdk = 36
        versionCode = 35
        versionName = "2.20.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // --- SECURE API KEY LOADING ---
        val secretProps = Properties()
        val propFile = rootProject.file("local.properties")

        var apiKey = ""
        if (propFile.exists()) {
            propFile.inputStream().use { stream ->
                secretProps.load(stream)
                apiKey = secretProps.getProperty("GROQ_API_KEY") ?: ""
            }
        }

        buildConfigField("String", "GROQ_API_KEY", "\"$apiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // Release 签名：从根目录 keystore.properties 读取（该文件不入库）；
    // 克隆者未配置时自动回退 debug 签名，确保人人可构建。
    val keystoreProps = Properties()
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        keystoreFile.inputStream().use { keystoreProps.load(it) }
    }
    val hasReleaseSigning = keystoreProps.getProperty("storeFile") != null

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/ASL2.0"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.core.splashscreen)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.material3)
    ksp(libs.androidx.room.compiler)

    implementation(libs.gson)
    implementation("com.github.chrisbanes:PhotoView:2.3.0")
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    implementation("com.google.guava:listenablefuture:1.0")
    implementation(libs.glide)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
