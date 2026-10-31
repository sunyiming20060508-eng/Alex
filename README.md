# Backend

Spring Boot 3.3.5 + JDK 17 + 普通 MyBatis（3.0.4，不是 MyBatis-Plus）+ MySQL + Redis，模块化单体。包结构和分层约定以 [../docs/PROJECT_STRUCTURE.md](../docs/PROJECT_STRUCTURE.md) 为准，做到哪一步以 [../docs/PROGRESS.md](../docs/PROGRESS.md) 为准，接口约定以 [../docs/API_CONTRACT.md](../docs/API_CONTRACT.md) 为准——本文件只写"怎么跑起来"，不重复那些内容。

## 启动

```powershell
cd D:\Test\test\my\backend
mvn clean spring-boot:run        # clean 不能省，见 docs/PROGRESS.md 第 4 节
```

IDEA 里直接运行 `LifeServiceApplication` 也可以，但**必须给运行配置加环境变量** `MYSQL_PASSWORD`，否则应用能起来、一连库就报错（登录接口表现为 500）。本机已经用 `setx MYSQL_PASSWORD "<你的密码>"` 设成用户级变量，改过之后要重启 IDEA 才生效。

## 配置与密钥

`src/main/resources/application.yml` 里所有可变项都是 `${ENV_NAME:默认值}` 形式，**仓库内不写真实口令**。MySQL 的默认值是课件示例值、和这台机器不一致，所以它只是"没设变量时会让启动失败得早一点"的占位，不是可用口令。Redis 同理。

支持的环境变量：`MYSQL_HOST` `MYSQL_PORT` `MYSQL_DATABASE` `MYSQL_USERNAME` `MYSQL_PASSWORD` `REDIS_HOST` `REDIS_PORT` `REDIS_PASSWORD` `SERVER_PORT` `UPLOAD_DIR` `UPLOAD_PUBLIC_PREFIX` `UPLOAD_MAX_SIZE_MB`。

## 依赖服务

需要 MySQL（库名 `life_service`，表由 `src/main/resources/db/migration/` 下的脚本建）和 Redis（本机是 Memurai，配置文件在 `D:\Test\redis\memurai.conf`）。

想用容器起这两个：`cd ..\infra; copy .env.example .env`（填上口令）` ; docker compose up -d mysql redis`。没有 `.env` 时 compose 会直接报错，不会用空密码启动。
