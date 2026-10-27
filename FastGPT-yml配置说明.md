# FastGPT YML 对接泛微 CAS 配置说明

本文档说明如何在 FastGPT 的 `fastgpt.yml` 或 `docker-compose.yml` 中接入本项目。

FastGPT 官方 SSO 文档没有内置 CAS Provider，但 FastGPT Pro 支持一套标准 SSO 接口。本项目的作用就是在中间进行协议适配：

```text
泛微 CAS <-- CAS 协议 --> fastgpt-sso-java <-- FastGPT 标准 SSO 接口 --> FastGPT Pro
```

因此，FastGPT 主服务无需支持 CAS，也不需要配置 CAS Server 地址。

## 1. 前提条件

- 使用支持外部成员系统的 FastGPT Pro/商业版。
- 已将 `fastgpt-sso-java:1.0` 镜像加载到 x86 服务器。
- 已在泛微统一认证管理端注册应用并获得 `appid`。
- 泛微 CAS 能访问 `fastgpt-sso-java` 的对外回调地址。
- 已准备好用户映射表 `user-mapping.csv`（CAS 账号 → FastGPT 用户），未映射的账号会被拒绝登录，见第 4.1 节。

加载镜像：

```bash
docker load -i fastgpt-sso-java-1.0-amd64.tar
```

## 2. 需要配置的两个服务

### 2.1 FastGPT Pro 服务

在 `fastgpt.yml` 中找到实际运行 FastGPT Pro 的服务。服务名可能是 `fastgpt`、`fastgpt-pro` 或者你自己定义的名称。

在该服务的 `environment` 中加入：

```yaml
environment:
  EXTERNAL_USER_SYSTEM_BASE_URL: http://fastgpt-sso:8080
  EXTERNAL_USER_SYSTEM_AUTH_TOKEN: ${FASTGPT_SSO_AUTH_TOKEN}
```

参数说明：

| 参数 | 含义 |
| --- | --- |
| `EXTERNAL_USER_SYSTEM_BASE_URL` | FastGPT Pro 访问 SSO 适配服务的内网地址 |
| `EXTERNAL_USER_SYSTEM_AUTH_TOKEN` | FastGPT 调用标准 SSO 接口时使用的 Bearer Token |

`EXTERNAL_USER_SYSTEM_BASE_URL` 应使用 Docker 服务名和容器端口，不要填公网地址，也不要填 `localhost`。

### 2.2 CAS 适配服务

在 `services` 下新增：

```yaml
fastgpt-sso:
  image: fastgpt-sso-java:1.0
  container_name: fastgpt-sso
  restart: always
  networks:
    - fastgpt
  ports:
    - "8080:8080"
  environment:
    SERVER_PORT: 8080
    SSO_PROVIDER: weaver
    SPRING_PROFILES_ACTIVE: weaver
    AUTH_TOKEN: ${FASTGPT_SSO_AUTH_TOKEN}

    # 泛微 CAS 配置
    WEAVER_SSO_ENABLED: "true"
    WEAVER_SSO_SERVER_URL: http://cas.sc.pbc.gov
    WEAVER_SSO_LOGIN_PATH: /sso/login
    WEAVER_SSO_APP_ID: ${WEAVER_SSO_APP_ID}

    # 本 SSO 适配服务的对外可访问地址，不要以 / 结尾
    WEAVER_SSO_SERVER_NAME: ${WEAVER_SSO_PUBLIC_URL}
    WEAVER_SSO_CALLBACK_PATH: /login/oauth/callback

    # Ticket 校验接口（兼容纯文本 yes\n账号 / CAS 2.0 XML / JSON）
    WEAVER_SSO_VALIDATE_PATH: /sso/validate
    # 泛微要求 service 与注册回调地址完全一致时改为 "false"
    WEAVER_SSO_SERVICE_INCLUDE_REDIRECT: "true"

    # 统一登出：/login/oauth/logout 会跳转泛微 /sso/logout，再回到此地址
    WEAVER_SSO_LOGOUT_REDIRECT_URL: ${FASTGPT_PUBLIC_URL}/login

    # CAS 账号 → FastGPT 用户 映射表（容器内路径，对应下方 volumes 挂载）
    WEAVER_SSO_USER_MAPPING_FILE: /app/config/user-mapping.csv

    # CAS 返回用户属性映射（JSON data / XML cas:attributes 中的字段名）
    WEAVER_SSO_ATTR_USERNAME: loginid
    WEAVER_SSO_ATTR_MEMBER_NAME: lastname
    WEAVER_SSO_ATTR_EMAIL: email
    WEAVER_SSO_ATTR_MOBILE: mobile

    # 仅在需要接收 HR 组织和人员同步时配置
    WEAVER_SSO_HR_SYNC_TOKEN: ${WEAVER_SSO_HR_SYNC_TOKEN}
  volumes:
    # 挂载目录而不是单个文件，否则宿主机上编辑后容器内可能看不到新内容
    - ./sso-config:/app/config:ro
```

> 如果 `fastgpt.yml` 已经定义了 `fastgpt` 网络，直接复用即可。如果实际网络名不是 `fastgpt`，需要将上面的网络名改成实际名称，并确保 FastGPT Pro 和 `fastgpt-sso` 处在同一 Docker 网络中。

## 3. 可直接参考的完整片段

下面的 `fastgpt-pro` 仅为示例服务名。实际使用时，不要重复创建 FastGPT Pro 服务，只需将两个 `EXTERNAL_USER_SYSTEM_*` 变量合并到现有服务的 `environment` 中。

```yaml
services:
  fastgpt-pro:
    # 保留你现有的 image、ports、volumes 和其他配置
    environment:
      # 保留原有环境变量
      EXTERNAL_USER_SYSTEM_BASE_URL: http://fastgpt-sso:8080
      EXTERNAL_USER_SYSTEM_AUTH_TOKEN: ${FASTGPT_SSO_AUTH_TOKEN}
    networks:
      - fastgpt
    depends_on:
      - fastgpt-sso

  fastgpt-sso:
    image: fastgpt-sso-java:1.0
    container_name: fastgpt-sso
    restart: always
    ports:
      - "8080:8080"
    environment:
      SERVER_PORT: 8080
      SSO_PROVIDER: weaver
      SPRING_PROFILES_ACTIVE: weaver
      AUTH_TOKEN: ${FASTGPT_SSO_AUTH_TOKEN}
      WEAVER_SSO_ENABLED: "true"
      WEAVER_SSO_SERVER_URL: http://cas.sc.pbc.gov
      WEAVER_SSO_LOGIN_PATH: /sso/login
      WEAVER_SSO_APP_ID: ${WEAVER_SSO_APP_ID}
      WEAVER_SSO_SERVER_NAME: ${WEAVER_SSO_PUBLIC_URL}
      WEAVER_SSO_CALLBACK_PATH: /login/oauth/callback
      WEAVER_SSO_VALIDATE_PATH: /sso/validate
      WEAVER_SSO_SERVICE_INCLUDE_REDIRECT: "true"
      WEAVER_SSO_LOGOUT_REDIRECT_URL: ${FASTGPT_PUBLIC_URL}/login
      WEAVER_SSO_USER_MAPPING_FILE: /app/config/user-mapping.csv
      WEAVER_SSO_ATTR_USERNAME: loginid
      WEAVER_SSO_ATTR_MEMBER_NAME: lastname
      WEAVER_SSO_ATTR_EMAIL: email
      WEAVER_SSO_ATTR_MOBILE: mobile
      WEAVER_SSO_HR_SYNC_TOKEN: ${WEAVER_SSO_HR_SYNC_TOKEN}
    volumes:
      - ./sso-config:/app/config:ro
    networks:
      - fastgpt

networks:
  fastgpt:
    # 如果原 fastgpt.yml 中已存在该网络，保留原配置，不要重复定义
```

## 4. `.env` 配置

在 `fastgpt.yml` 同级的 `.env` 文件中配置：

```properties
# FastGPT Pro 和 fastgpt-sso-java 共用的调用鉴权 Token
# 建议使用至少 32 位随机字符，不要使用示例值
FASTGPT_SSO_AUTH_TOKEN=replace-with-a-long-random-token

# 泛微统一认证管理端分配的 appid
WEAVER_SSO_APP_ID=replace-with-appid

# 浏览器和泛微 CAS 能访问的 SSO 适配服务地址
# 直接暴露端口示例：http://192.168.1.20:8080
# 反向代理示例：https://fastgpt-sso.example.com
WEAVER_SSO_PUBLIC_URL=http://192.168.1.20:8080

# 用户访问 FastGPT 的地址，用于 CAS 登出后回跳登录页
FASTGPT_PUBLIC_URL=https://fastgpt.example.com

# 可选：HR 同步鉴权 Token
WEAVER_SSO_HR_SYNC_TOKEN=replace-with-hr-sync-token
```

最重要的是下面两个值必须一致：

```text
FastGPT Pro: EXTERNAL_USER_SYSTEM_AUTH_TOKEN
SSO 服务:   AUTH_TOKEN
```

本文档中它们都引用同一个 `.env` 变量 `FASTGPT_SSO_AUTH_TOKEN`，可以避免配错。

### 4.1 用户映射表

CAS 账号与 FastGPT 用户通过映射表匹配，**不在表中或 `status=0` 的账号会被拒绝登录**。

在 `fastgpt.yml` 同级目录准备：

```bash
mkdir -p ./sso-config
# 示例文件位于 fastgpt-sso-java 项目的 config/user-mapping.example.csv
cp user-mapping.example.csv ./sso-config/user-mapping.csv
```

文件内容（UTF-8，`#` 开头为注释，第一个非注释行为表头）：

```csv
cas_account,fastgpt_username,member_name,contact,status
xitongceshi.cd,xitongceshi,系统测试,ceshi@example.com,1
zhangsan.cd,zhangsan,张三,,1
lisi.cd,lisi,,,0
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `cas_account` | 是 | 泛微 CAS 校验返回的账号，不区分大小写 |
| `fastgpt_username` | 是 | 登录后 FastGPT 中的用户名，建议与成员同步的 `loginid` 一致 |
| `member_name` | 否 | 姓名，为空时使用 CAS / HR 同步数据 |
| `contact` | 否 | 联系方式，为空时使用 CAS / HR 同步数据 |
| `status` | 否 | `0` 停用，空或其他值启用 |

维护方式：

- 直接编辑宿主机上的 `./sso-config/user-mapping.csv`，保存后自动生效，**不需要重启容器**。
- 停用人员把 `status` 改为 `0`，不建议直接删除。
- 不确定 `cas_account` 怎么填时，让用户登录一次，然后执行 `docker logs fastgpt-sso | grep casAccount=` 查看实际账号。
- 用 Excel 编辑时另存为「CSV UTF-8」；字段内不能含英文逗号。

更完整的维护说明见 [配置说明.md](./配置说明.md) 第 8 节。

## 5. 地址不要混淆

| 地址 | 填写内容 | 谁使用 |
| --- | --- | --- |
| `EXTERNAL_USER_SYSTEM_BASE_URL` | `http://fastgpt-sso:8080` | FastGPT Pro 容器内部访问 |
| `WEAVER_SSO_SERVER_URL` | `http://cas.sc.pbc.gov` | Java 适配服务访问泛微 CAS |
| `WEAVER_SSO_SERVER_NAME` | 如 `http://192.168.1.20:8080` 或 `https://fastgpt-sso.example.com` | 浏览器和 CAS 回调 Java 服务 |
| `FASTGPT_PUBLIC_URL` | 如 `https://fastgpt.example.com` | 用户最终访问 FastGPT；CAS 登出后回到这里 |
| `WEAVER_SSO_USER_MAPPING_FILE` | `/app/config/user-mapping.csv` | 容器内路径，对应宿主机 `./sso-config/user-mapping.csv` |

`WEAVER_SSO_SERVER_NAME` 不能配置为：

- `http://fastgpt-sso:8080`，因为这是 Docker 内部地址，浏览器和泛微 CAS 通常无法解析。
- `http://localhost:8080`，因为回调时 `localhost` 指向用户电脑或 CAS 服务器自身。
- FastGPT 前端地址，因为 CAS Ticket 需要先由 Java 适配服务校验。

## 6. 泛微管理端配置

在泛微统一认证中注册应用时：

- `appid`：与 `WEAVER_SSO_APP_ID` 一致。
- 应用服务地址：与 `WEAVER_SSO_SERVER_NAME` 一致。
- CAS 回调地址：

```text
${WEAVER_SSO_PUBLIC_URL}/login/oauth/callback
```

例如：

```text
http://192.168.1.20:8080/login/oauth/callback
```

- 登出服务地址（如管理端需要填写）：`${FASTGPT_PUBLIC_URL}/login`。

如果泛微只接受与上面完全一致的 `service`（不允许带 `?redirect_uri=...` 等参数），需要配置 `WEAVER_SSO_SERVICE_INCLUDE_REDIRECT: "false"`。

## 7. FastGPT 商业版后台配置

YML 配置完成后，还需要在 FastGPT 商业版管理后台中配置 SSO 登录按钮的文案、图标和是否启用。

FastGPT 官方文档：

- [SSO & 外部成员同步](https://doc.fastgpt.io/zh-CN/guide/admin/sso)

## 8. 成员同步（可选）

当泛微的组织和成员数据已经推送到本项目后，FastGPT Pro 可以通过下列接口获取数据：

- `/org/list`
- `/user/list`

如果需要 FastGPT 定时自动同步，可在 FastGPT Pro 的 `environment` 中加入：

```yaml
SYNC_MEMBER_CRON: "0 0 * * *"
```

该 Cron 使用 UTC 时区。例如北京时间 12:00 执行，应配置为：

```yaml
SYNC_MEMBER_CRON: "0 4 * * *"
```

## 9. 启动和检查

启动：

```bash
docker compose -f fastgpt.yml up -d
```

检查 SSO 服务：

```bash
curl http://127.0.0.1:8080/test
```

预期返回：

```text
FastGPT-SSO-Service
```

检查 FastGPT Pro 容器是否能访问 SSO 服务：

```bash
docker exec <FastGPT-Pro容器名> wget -qO- http://fastgpt-sso:8080/test
```

如果容器内没有 `wget`，可以使用容器内已安装的 `curl` 进行检查。

检查映射表是否挂载成功：

```bash
docker exec fastgpt-sso cat /app/config/user-mapping.csv
```

有人登录后，日志中应出现：

```text
已加载用户映射文件: /app/config/user-mapping.csv, 共 N 条
```

## 10. 常见错误

### FastGPT 调用 SSO 返回 401

检查 `EXTERNAL_USER_SYSTEM_AUTH_TOKEN` 和 `AUTH_TOKEN` 是否完全一致。

### FastGPT 容器访问不到 SSO 服务

检查两个容器是否加入同一 Docker 网络，并确保地址为 `http://fastgpt-sso:8080`。

### CAS 登录后无法回调

检查 `WEAVER_SSO_SERVER_NAME` 是否为浏览器和 CAS 都能访问的真实地址，以及防火墙是否放行 8080 端口。

### CAS Ticket 校验失败

重点检查：

- `WEAVER_SSO_SERVER_URL`
- `WEAVER_SSO_LOGIN_PATH`
- `WEAVER_SSO_APP_ID`
- `WEAVER_SSO_SERVER_NAME`
- `WEAVER_SSO_VALIDATE_PATH`（日志出现 `HTTP 404` 时重点检查）
- 泛微管理端登记的应用地址

CAS 通常要求 Ticket 申请时的 `service` 与校验时完全一致，包括协议、域名、端口和路径。校验失败时会自动重新跳 CAS 登录最多 2 次，之后显示「统一认证登录异常」。

### CAS 登录后提示「未开通 FastGPT 账号」或「已停用」

- 执行 `docker logs fastgpt-sso | grep casAccount=`，确认实际 CAS 账号后补充到映射表。
- 检查该行 `status` 是否为 `0`。
- 日志出现 `用户映射文件不可读` 时，检查 `volumes` 挂载和 `WEAVER_SSO_USER_MAPPING_FILE` 路径。

### 退出后再次登录没有要求输入密码

CAS 会话未清理。退出时需要访问 `${WEAVER_SSO_PUBLIC_URL}/login/oauth/logout`，它会跳转泛微 `/sso/logout` 后回到 `WEAVER_SSO_LOGOUT_REDIRECT_URL`。
