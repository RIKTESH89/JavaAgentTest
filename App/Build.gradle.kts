// app/build.gradle.kts
// This is the main build file for the Android application module.
plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.example.langgraphchatbot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.langgraphchatbot"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    // Packaging options to handle potential conflicts with duplicate files from dependencies.
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/native-image/**"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    // Standard Android dependencies
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    // Langchain4j and Langgraph4j dependencies
    // We use the 'langchain4j' library for core AI functionalities.
    implementation("dev.langchain4j:langchain4j:0.32.0")
    // An in-memory chat model for easy testing without API keys.
    implementation("dev.langchain4j:langchain4j-in-memory:0.32.0")

    // Langgraph4j for creating the agent graph.
    // The core library for building stateful, multi-agent applications.
    implementation("com.github.langgraph4j.langgraph4j:langgraph4j-core:main-SNAPSHOT")
    // The langchain4j integration module for langgraph4j.
    implementation("com.github.langgraph4j.langgraph4j:langchain4j-agent:main-SNAPSHOT")

    // Required for JSON serialization within langgraph4j
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.fasterxml.jackson.core:jackson-core:2.17.1")
    implementation("com.fasterxml.jackson.core:jackson-annotations:2.17.1")

}

