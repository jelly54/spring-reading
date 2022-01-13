# Spring Framework

源码阅读 && 追加阅读笔记

- version: spring 5.0.x
- last git: 原项目最后一次git提交 2020/11/25 at 01:30
- 项目原 github 地址 ：[spring-framework](https://github.com/spring-projects/spring-framework)

# 初步搭建

1. 修改仓库

找到 `build.gradle` 修改以下两个地方

```
repositories {
	maven { url "https://maven.aliyun.com/repository/spring-plugin" }
//	gradlePluginPortal()
//	maven { url "https://repo.spring.io/plugins-release" }
}
```

```
repositories {
	maven { url "http://maven.aliyun.com/nexus/content/groups/public" }
	maven { url "https://repo.spring.io/libs-spring-framework-build" }
}
```

2. 项目路径下执行构建测试

```shell
gradlew :spring-oxm:compileTestJava
```