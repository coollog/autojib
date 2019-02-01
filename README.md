![experimental](https://img.shields.io/badge/stability-experimental-red.svg)
[![Gitter chat](https://badges.gitter.im/coollog/autojib.png)](https://gitter.im/coollog/autojib)

# AutoJib

**Note: This is an experimental prototype. NOT ready for production.**

Add AutoJib as a dependency and your application will containerize itself.

# Quickstart

Install:

```bash
./gradlew install
```

Add AutoJib as dependency:

`build.gradle`
```groovy
dependencies {
  implementation 'coollog.experiments:autojib:0.1.0-SNAPSHOT'
}
```

Run with AutoJib main class:

`build.gradle`
```groovy
task run(type: JavaExec) {
  classpath = sourceSets.main.runtimeClasspath
  main = 'coollog.experiments.autojib.Main'
  environment AUTOJIB_IMAGE: <YOUR IMAGE>
}
```

Self-containerize:

```bash
$ gradle run
``` 

# Example app

`test-app/build.gradle` - play around with `run` task configuration.

Self-containerize:

```bash
(cd test-app && ./gradlew run)
```

Use docker to run the built image:

```bash
docker run ${AUTOJIB_IMAGE}
```
