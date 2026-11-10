plugins {
    `java-gradle-plugin`
}

repositories {
    google()
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("com.android.tools.build:gradle-api:8.13.2")
    implementation("org.ow2.asm:asm:9.11.1")
    implementation("org.ow2.asm:asm-tree:9.8")
    testImplementation("junit:junit:4.13.2")
    // Read the exact upstream bytes in regression tests; never load its Android classes.
    testRuntimeOnly("com.umeng.umsdk:umdid:1.1.0")
}

gradlePlugin {
    plugins {
        create("pushTls") {
            id = "com.laoke.weshirt.push-tls"
            implementationClass = "com.laoke.weshirt.buildlogic.PushTlsPlugin"
        }
    }
}

tasks.test {
    useJUnit()
    // Gradle ProjectBuilder's in-process fixture needs access to ClassLoader on JDK 17+.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
}
