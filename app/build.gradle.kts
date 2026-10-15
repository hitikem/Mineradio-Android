plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mineradio.android"
    compileSdk = 35

    // 钉住 NDK 版本：AGP 8.7 默认会去找 27.0.12077973，
    // 而本项目在 26.1.10909125 上验证通过，不显式指定会直接报「NDK not configured」。
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.mineradio.android"
        // minSdk 26：通知渠道与前台服务在 8.0 之后才有完整支持，
        // 而 8.0 以下的存量设备已可忽略，换来的是通知/媒体会话代码能直接用框架 API。
        minSdk = 26
        targetSdk = 35

        // 与上游 Mineradio 版本对齐，后缀标识安卓移植线
        versionCode = 1
        versionName = "2.2.0-android.1"

        // libnode.so 每个架构 58~65MB，打进一个包会非常大。
        // 按 ABI 拆分，另外额外产出一个含全部架构的 universal 包方便分发。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            // 同时产出一个全架构包，用户不确定自己机型时可以直接装它
            isUniversalApk = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 使用 debug 签名，保证 clone 下来就能直接出可安装的 release APK
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    packaging {
        jniLibs {
            // nodejs-mobile 的 libnode.so 需要被解压到 nativeLibraryDir 才能被动态链接器找到
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*"
            )
        }
    }

    androidResources {
        // 运行时资源包本身已是 deflate 压缩，再压一次没收益，
        // 而且不压缩才能用 openFd() 拿到总长度来做解压进度。
        noCompress += "zip"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // AGP 8 起 BuildConfig 默认不生成，WebView 调试开关依赖它
        buildConfig = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}

// ---------------------------------------------------------------------------
// 把 node-app/ 打成单个 zip 放进 assets
//
// 为什么不直接把目录塞进 assets：node-app 有近 3000 个文件（node_modules 占 2826 个），
// 逐文件解压首次启动要十几秒。打成 zip 后只需顺序读一个流，快得多，APK 也更小。
// ---------------------------------------------------------------------------
val nodeAppDir = rootProject.layout.projectDirectory.dir("node-app")
val nodeAppZipName = "mineradio-node-app.zip"

val prepareNodeAppZip by tasks.registering(Zip::class) {
    group = "mineradio"
    description = "把 node-app/ 打包成 assets/mineradio-node-app.zip"

    val required = listOf(
        "main.js",
        "server.js",
        "public/index.html",
        "node_modules/NeteaseCloudMusicApi/package.json"
    )
    doFirst {
        val missing = required.filterNot { nodeAppDir.file(it).asFile.exists() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "node-app 内容不完整，缺少：${missing.joinToString(", ")}\n" +
                    "请先执行：\n" +
                    "  bash scripts/sync-node-app.sh <上游 Mineradio 源码目录>\n" +
                    "  bash scripts/fetch-node-app-deps.sh"
            )
        }
    }

    from(nodeAppDir)
    archiveFileName.set(nodeAppZipName)
    destinationDirectory.set(layout.projectDirectory.dir("src/main/assets"))

    // 只排掉明确无用的东西。刻意不动 node_modules 里的 test/ 或文档目录：
    // 少省一两 MB，换来的是不用赌某个包运行期会不会去读自己的附带文件。
    exclude("**/.DS_Store", "**/Thumbs.db")
    exclude("**/node_modules/.cache/**")

    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// 保证任何构建路径（含 IDE 直接运行）都会先生成资源包
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(prepareNodeAppZip)
}
