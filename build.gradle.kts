import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
}

group = "com.jobpilot"
version = "0.1.0"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

// playwright 与 driver-bundle 版本同源，改这里就行
val playwrightVersion = "1.62.0"

repositories {
    mavenCentral()
}

dependencies {
    // 用 Spring Boot 官方 BOM 管理版本
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.baomidou:mybatis-plus-spring-boot3-starter:3.5.9")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // 浏览器自动化：playwright-java 只是壳，真正干活的是 Node driver 进程，
    // 由 installPatchrightDriver 任务装配（patchright = 打了反检测补丁的 playwright）。
    // 与 patchright-core 1.62.1 对齐，勿降 1.51：1.51.3 的 locator.count() 是坏的。
    implementation("com.microsoft.playwright:playwright:$playwrightVersion")

    // Lombok：compileOnly 管编译期可见性，annotationProcessor 管代码生成，缺一不可
    val lombok = "org.projectlombok:lombok:1.18.42"
    compileOnly(lombok)
    annotationProcessor(lombok)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testCompileOnly(lombok)
    testAnnotationProcessor(lombok)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

springBoot {
    buildInfo()
}

// ---------------------------------------------------------------------------
// Patchright driver
//
// playwright-java 通过 -Dplaywright.cli.dir 定位 driver，要求目录结构：
//     <driverDir>/package/cli.js
// patchright-core 官方没有 Java 版，用 npm 装好后把包内容搬到 package/ 下即可。
// 装好的 driver 同时会被 bundlePatchrightDriver 打进 jar（patchright/ 前缀），
// 供 jpackage 打包版运行时解压到用户数据目录——打包机器上没有 node 也能跑。
// ---------------------------------------------------------------------------
val patchrightVersion = "1.62.1"
val patchrightDriverDir = layout.buildDirectory.dir("patchright-driver")

val installPatchrightDriver by tasks.registering {
    group = "playwright"
    description = "安装 patchright-core 并装配成 playwright-java 能识别的 driver 目录"

    val outDir = patchrightDriverDir
    outputs.dir(outDir)

    doLast {
        val driverDir = outDir.get().asFile
        val packageDir = File(driverDir, "package")
        val marker = File(driverDir, ".version")

        if (marker.isFile && marker.readText().trim() == patchrightVersion && File(packageDir, "cli.js").isFile) {
            logger.lifecycle("patchright-core $patchrightVersion 已就绪: $packageDir")
            return@doLast
        }

        driverDir.deleteRecursively()
        driverDir.mkdirs()

        logger.lifecycle("正在安装 patchright-core@$patchrightVersion ...")
        val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"
        providers.exec {
            commandLine(npm, "install", "patchright-core@$patchrightVersion",
                    "--prefix", driverDir.absolutePath, "--no-audit", "--no-fund", "--loglevel=error")
        }.result.get().assertNormalExitValue()

        val installed = File(driverDir, "node_modules/patchright-core")
        if (!File(installed, "cli.js").isFile) {
            throw GradleException("patchright-core 安装后没找到 cli.js: $installed")
        }
        copy {
            from(installed)
            into(packageDir)
        }
        marker.writeText(patchrightVersion)
        logger.lifecycle("patchright driver 已装配: $packageDir")
    }
}

// driver 进 jar：processResources 之前同步到 build/resources/main/patchright/
val bundlePatchrightDriver by tasks.registering(Sync::class) {
    group = "playwright"
    description = "把 patchright driver 复制进 jar 资源，供打包版解压使用"
    dependsOn(installPatchrightDriver)
    from(patchrightDriverDir.map { it.dir("package") })
    into(layout.buildDirectory.dir("resources/main/patchright"))
}

tasks.named("processResources") {
    dependsOn(bundlePatchrightDriver)
}

// ---------------------------------------------------------------------------
// driver-bundle 瘦身
//
// playwright 的 driver-bundle 依赖带全部平台的 node（5 份，600MB+），mac 包和 win
// 包各自只跑自己那一份，剩下的是白背的重量。做法：装一个只含当前平台 node 的
// 裁剪版依赖，替换掉 runtimeClasspath 里那个全量的。
//
// 注意不能图省事去重写 bootJar 产出的 fat jar：用 java.util.zip 复制一遍就会破坏
// Spring Boot loader 对嵌套 jar 的读取约定（实测重写后 BOOT-INF/lib 整个加载不到，
// NoClassDefFoundError: org/slf4j/LoggerFactory），所以裁剪必须发生在打包之前。
//
// 平台目录名必须与 PlaywrightDriverSupport.nodePlatformDir() 以及 driver-bundle
// 内部 DriverJar 的映射保持一致（三者都是 win32_x64 / mac-arm64 / mac /
// linux-arm64 / linux），改一处就要同步另两处。
// ---------------------------------------------------------------------------
// 裁前的全量 bundle 从这里取；版本与上面的 playwright 依赖同源
val driverBundleSource by configurations.creating {
    isCanBeResolved = true
}
dependencies {
    driverBundleSource("com.microsoft.playwright:driver-bundle:$playwrightVersion")
}

// 全量的从运行期依赖里拿掉，换成裁剪版（见下面 runtimeOnly(files(...))）。
// 排除规则挂在 runtimeOnly 上而不是 runtimeClasspath：exclude 只顺着 extendsFrom
// 往下继承，而 testRuntimeClasspath 走的是 testRuntimeOnly -> runtimeOnly 这条链，
// 挂在 runtimeClasspath 上测试类路径照样会拖进全量 202MB 的 bundle。
configurations.named("runtimeOnly") {
    exclude(group = "com.microsoft.playwright", module = "driver-bundle")
}

val trimmedDriverBundle = layout.buildDirectory.file("trimmed/driver-bundle-$playwrightVersion.jar")

val trimDriverBundle by tasks.registering {
    group = "playwright"
    description = "裁掉 driver-bundle 里非当前平台的 node，给打包瘦身"

    val sourceJar = provider {
        driverBundleSource.incoming.artifactView { lenient(true) }.files
                .firstOrNull { it.name.startsWith("driver-bundle-") && it.name.endsWith(".jar") }
                ?: throw GradleException("没解析到 driver-bundle jar，裁剪无从谈起")
    }
    inputs.property("platformDir", provider {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val arm = arch.contains("aarch64") || arch.contains("arm64")
        when {
            os.contains("mac") -> if (arm) "mac-arm64" else "mac"
            os.contains("win") -> "win32_x64"
            os.contains("linux") -> if (arm) "linux-arm64" else "linux"
            else -> null
        }
    })
    inputs.file(sourceJar)
    outputs.file(trimmedDriverBundle)

    doLast {
        val platform = (inputs.properties["platformDir"] as String?)
                ?: throw GradleException("未知平台，不裁剪 driver-bundle: os.name=${System.getProperty("os.name")}")
        val source = sourceJar.get()
        val nodeName = "driver/$platform/" + if (platform.startsWith("win32")) "node.exe" else "node"
        // driver-bundle 里只有各平台的 node 二进制和 LICENSE，playwright 自己的
        // driver JS 在另一个 artifact（driver-*.jar 的 driver/package/）里，不裁它，
        // 所以这里只留当前平台那一份
        val keepPrefixes = listOf("driver/$platform/", "META-INF/")

        val out = trimmedDriverBundle.get().asFile
        out.parentFile.mkdirs()
        val tmp = File(out.parentFile, out.name + ".trimming")
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { nested ->
                ZipFile(source).use { zip ->
                    for (entry in zip.entries().toList()) {
                        if (!entry.isDirectory && keepPrefixes.any { entry.name.startsWith(it) }) {
                            nested.putNextEntry(ZipEntry(entry.name))
                            zip.getInputStream(entry).use { it.copyTo(nested) }
                            nested.closeEntry()
                        }
                    }
                }
            }
            // 裁完了必须还能用：当前平台的 node 缺了就是失败
            ZipFile(tmp).use { check ->
                if (check.getEntry(nodeName) == null) {
                    throw GradleException("裁剪后 driver-bundle 缺少 $nodeName")
                }
            }
            if (!tmp.renameTo(out)) throw GradleException("写出裁剪版 driver-bundle 失败: $out")
        } finally {
            tmp.delete()
        }
        logger.lifecycle("driver-bundle 已裁剪为 $platform: ${out.length() / 1024 / 1024}MB")
    }
}

dependencies {
    runtimeOnly(files(trimmedDriverBundle.map { it.asFile }).builtBy(trimDriverBundle))
}

fun findOnPath(name: String): File? {
    val candidates = if (System.getProperty("os.name").lowercase().contains("windows")) {
        listOf("$name.cmd", "$name.exe", "$name.bat", name)
    } else {
        listOf(name)
    }
    val dirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
    for (dir in dirs) {
        if (dir.isBlank()) continue
        for (candidate in candidates) {
            val file = File(dir.trim(), candidate)
            if (file.isFile) return file
        }
    }
    return null
}

tasks.named<JavaExec>("bootRun") {
    dependsOn(installPatchrightDriver)
    systemProperty("playwright.cli.dir", patchrightDriverDir.get().asFile.absolutePath)
    findOnPath("node")?.let { environment("PLAYWRIGHT_NODEJS_PATH", it.absolutePath) }
    // 开发态每重启一次就弹浏览器太吵，地址固定 9527 也不需要自动打开
    systemProperty("jobpilot.open-page", "false")
}
