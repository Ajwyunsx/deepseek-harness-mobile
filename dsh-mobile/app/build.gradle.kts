plugins {
    id("com.android.application")
}

android {
    namespace = "com.dshmobile.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dshmobile.app"
        minSdk = 26
        // targetSdk 必须 ≤28：Android 10+ 对 targetSdk 29+ 的应用启用 SELinux 域
        // 限制，禁止 exec 私有目录里的二进制（proot/node 都跑不了），Termux 同理。
        // 这是硬性冲突，因此 Android 16（API 36）适配走另一条路：
        // targetSdk 保持 28（Android 16 仍可正常安装运行），权限全部显式申请
        // （POST_NOTIFICATIONS / MANAGE_EXTERNAL_STORAGE / 前台服务声明），
        // 16KB 页设备在安装期由 BootstrapInstaller 检测并告警。
        targetSdk = 28
        versionCode = 33
        versionName = "1.0.32"
    }

    lint {
        abortOnError = false
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }

    signingConfigs {
        create("release") {
            storeFile = file("../dsh-mobile.keystore")
            storePassword = "dshmobile2026"
            keyAlias = "dshmobile"
            keyPassword = "dshmobile2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
}
