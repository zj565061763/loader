[![Maven Central](https://img.shields.io/maven-central/v/io.github.zj565061763.android/loader)](https://central.sonatype.com/search?q=g:io.github.zj565061763.android+loader)

# Gradle

本库面向 Kotlin，且不会传递协程依赖，使用方需要同时添加兼容版本的 `kotlinx-coroutines`：

```kotlin
implementation("io.github.zj565061763.android:loader:$version")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
```

# Changelog

版本更新记录：[CHANGELOG.md](CHANGELOG.md)
