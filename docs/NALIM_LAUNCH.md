# Запуск Nephritis с Nalim (нативный бэкенд)

Источник флагов: README Nalim `v1.1`. Под Forge 1.20.1 сочетание флагов в этом документе не запускалось.

Без этих флагов мод работает на Java-пути: в логе `NalimBFS=unavailable`, `native=0 java=N`.

## 1. Требования

- HotSpot с JVMCI. Nalim строится на JVMCI; README Nalim перечисляет платформы Linux amd64/aarch64, macOS amd64/aarch64, Windows amd64 и не упоминает OpenJ9. На OpenJ9 нативный путь недоступен, мод остаётся на Java-пути.
- Nalim v1.1 проверен на JDK 17.0.20 (Ubuntu): нативный путь активен. На JDK 21.0.x тот же jar падает при установке кода с `IllegalArgumentException: nmethod entry barrier is missing`, мод остаётся на Java-пути. Для Forge 1.20.1 используйте JDK 17.
- Модуль `jdk.internal.vm.ci` должен присутствовать в целевой JRE. Урезанный рантайм лаунчера может его не содержать.

Проверка модуля:

```
java --list-modules | grep jdk.internal.vm.ci
```

Проверка, что JVM стартует с JVMCI:

```
java -XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -version
```

Обе команды выполнены на JDK 21.0.10 (Ubuntu): модуль найден, JVM стартовала. На JDK 17.0.20 (Ubuntu) модуль `jdk.internal.vm.ci` тоже найден. На вашей JRE лаунчера не выполнялись.

## 2. Вариант A: javaagent (проще)

Агент сам экспортирует нужные внутренние пакеты JDK.

```
-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -javaagent:<путь>/nalim.jar
```

## 3. Вариант B: без агента

Экспорты добавляются вручную:

```
-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI
--add-exports jdk.internal.vm.ci/jdk.vm.ci.code=ALL-UNNAMED
--add-exports jdk.internal.vm.ci/jdk.vm.ci.code.site=ALL-UNNAMED
--add-exports jdk.internal.vm.ci/jdk.vm.ci.hotspot=ALL-UNNAMED
--add-exports jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED
--add-exports jdk.internal.vm.ci/jdk.vm.ci.runtime=ALL-UNNAMED
```

`nalim.jar` при этом должен быть в classpath.

## 4. Конфигурация runClient/runServer в этом проекте

`build.gradle` добавляет в `runClient` и `runServer`, когда есть `libs/nalim.jar`:

```
-XX:+UnlockExperimentalVMOptions
-XX:+EnableJVMCI
--add-modules=jdk.internal.vm.ci
-Xbootclasspath/a:<абсолютный путь>/libs/nalim.jar
-javaagent:<абсолютный путь>/libs/nalim.jar
--add-exports=jdk.internal.vm.ci/jdk.vm.ci.code=ALL-UNNAMED
--add-exports=jdk.internal.vm.ci/jdk.vm.ci.code.site=ALL-UNNAMED
--add-exports=jdk.internal.vm.ci/jdk.vm.ci.hotspot=ALL-UNNAMED
--add-exports=jdk.internal.vm.ci/jdk.vm.ci.meta=ALL-UNNAMED
--add-exports=jdk.internal.vm.ci/jdk.vm.ci.runtime=ALL-UNNAMED
```

## 5. Клиент

Флаги из разделов 2 или 3 добавляются в поле JVM-аргументов лаунчера профиля Minecraft 1.20.1.

## 6. Сервер

Флаги добавляются в JVM-аргументы скрипта запуска сервера (в `user_jvm_args.txt`, если ваш сервер запускается через `run.bat`/`run.sh`; наличие этого файла в вашей сборке не проверялось).

## 7. Ограничения Nalim v1.1

- Функция `static`.
- Нет `JNIEnv`: нельзя бросать исключения и вызывать JVM.
- Аргументы только примитивы и примитивные массивы.
- Функция должна возвращаться быстро: она блокирует достижение safepoint для всей JVM.
