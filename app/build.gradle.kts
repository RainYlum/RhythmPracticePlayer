import java.util.Properties

// 应用版本号：单一来源，供 defaultConfig 与发布产物文件名（rhythmplayer-1.0.3.apk）共用
val appVersionName = "1.0.3"
val appVersionCode = 4

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 正式签名配置（ADR：密钥不入库；app/sign/ 已 gitignore，缺失时 release 不签名）
val keystoreProps = Properties().apply {
    val f = rootProject.file("app/sign/key.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.rythm.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rythm.app"
        minSdk = 29
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            // mockito inline mock maker 需要动态 agent（JDK 21+ 默认禁止自 attach）；
            // Byte Buddy < Java25 支持:先开实验模式（见报错建议）
            all {
                it.jvmArgs(
                    "-XX:+EnableDynamicAgentLoading",
                    "-Djdk.attach.allowAttachSelf=true",
                    "-Dnet.bytebuddy.experimental=true",
                )
            }
        }
    }
}

// 发布产物命名：项目简写 + 版本号（如 rhythmplayer-1.0.3.apk），仅 release 变体
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("rhythmplayer-${appVersionName}.apk")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockito.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}