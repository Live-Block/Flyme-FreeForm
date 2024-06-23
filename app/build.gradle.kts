val preference_version = "1.2.0"
val room_version = "2.6.1"
val shizuku_version = "13.1.5"
    
plugins {
    id("com.android.application")
    id("kotlin-android-extensions")
    id("kotlin-kapt")
    id("org.jetbrains.kotlin.android")
    id("stringfog")
    id("dev.rikka.tools.refine")
}

android {
    namespace = "com.sunshine.freeform"
    compileSdk = 34
    buildToolsVersion = "34.0.3"
    
    defaultConfig {
        applicationId = "io.liveblock.freeform"
        minSdk = 28
        targetSdk = 34
        versionCode = 3110
        versionName = "3.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isZipAlignEnabled = true
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), 
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
        
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

configurations.all {
    exclude(group = "androidx.appcompat", module = "appcompat")
}

dependencies {

    implementation("androidx.core:core-ktx:1.8.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity:1.9.0")
    implementation("androidx.lifecycle:lifecycle-livedata:2.5.1")
    implementation("androidx.navigation:navigation-ui:2.5.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.5.1")
    implementation("androidx.navigation:navigation-fragment:2.5.3")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    
    // 非主要
    implementation("junit:junit:4.13.2")
    implementation("androidx.test.ext:junit:1.1.3")
    implementation("androidx.test.espresso:espresso-core:3.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.5.1")
    
    compileOnly(files("libs/XposedBridgeAPI-89.jar"))
    
    // Rikka
    implementation("dev.rikka.rikkax.appcompat:appcompat:1.5.0.1")
    implementation("dev.rikka.rikkax.widget:borderview:1.1.0")
    implementation("dev.rikka.rikkax.recyclerview:recyclerview-ktx:1.3.1")

    implementation("dev.rikka.shizuku:api:$shizuku_version")
    implementation("dev.rikka.shizuku:provider:$shizuku_version")

    implementation("androidx.room:room-runtime:$room_version")
    kapt ("androidx.room:room-compiler:$room_version")
    implementation("androidx.room:room-ktx:$room_version")

    implementation("androidx.preference:preference-ktx:$preference_version")
    implementation("dev.rikka.rikkax.preference:simplemenu-preference:1.0.3")

    implementation("com.github.promeg:tinypinyin:3.0.0")

    implementation("com.airbnb.android:lottie:5.2.0")

    implementation("com.github.bumptech.glide:glide:4.13.2")

    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    implementation("com.github.megatronking.stringfog:xor:5.0.0")

    implementation("dev.rikka.tools.refine:runtime:4.1.0")
        
    compileOnly(project(":hidden-api"))
}

stringfog {
    // 必要：加解密库的实现类路径，需和上面配置的加解密算法库一致。
    implementation = "com.github.megatronking.stringfog.xor.StringFogImpl"
    // 可选：加密开关，默认开启。
    enable = true
    // 可选：指定需加密的代码包路径，可配置多个，未指定将默认全部加密。
    fogPackages = arrayOf("com.sunshine.freeform")
}