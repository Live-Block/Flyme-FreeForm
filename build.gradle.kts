// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    dependencies {
        classpath("com.github.megatronking.stringfog:gradle-plugin:5.1.0")
        // 选用加解密算法库，默认实现了xor算法，也可以使用自己的加解密库。
        classpath("com.github.megatronking.stringfog:xor:5.0.0")
        classpath("dev.rikka.tools.refine:gradle-plugin:4.4.0")
    }
}
plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    id("org.jetbrains.kotlin.kapt") version "1.7.22"
}

tasks.withType(JavaCompile::class.java) {
    options.compilerArgs.add("-Xlint:all")
}