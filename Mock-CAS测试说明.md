# Mock 泛微 CAS 本地测试说明

这个 mock 服务用于在没有真实泛微 Ecology 统一认证中心时，验证 `fastgpt-sso-java` 镜像的 CAS 登录、Ticket 校验、Assertion 属性读取和 FastGPT `code` 换用户信息流程。

## 1. 启动 mock CAS 和 SSO 镜像

先重新构建 SSO 镜像，确保镜像里包含当前代码：

```bash
docker build -t fastgpt-sso-java:1.0 .
```

如果是在 Mac 上构建、给 x86 服务器使用：

```bash
docker buildx build --platform linux/amd64 -t fastgpt-sso-java:1.0 --load .
```

确认镜像存在：

```bash
docker images fastgpt-sso-java:1.0
```

启动：

```bash
docker compose -f docker-compose.mock-cas.yml up -d --build
```

如果服务器是老版 Docker Compose，也可以用：

```bash
docker-compose -f docker-compose.mock-cas.yml up -d --build
```

服务地址：

```text
Mock CAS: http://127.0.0.1:18080
SSO 服务:  http://127.0.0.1:8080
```

## 2. 浏览器测试前的 hosts 配置

compose 中 `WEAVER_SSO_SERVER_URL` 配为 `http://mock-cas:18080`，这样 SSO 容器能访问 CAS 容器。

如果要用宿主机浏览器直接打开 FastGPT 返回的 `authURL`，宿主机也需要能解析 `mock-cas`：

```text
127.0.0.1 mock-cas fastgpt-sso
```

macOS/Linux 写入 `/etc/hosts`，Windows 写入 `C:\Windows\System32\drivers\etc\hosts`。

如果只用下面的 `curl --resolve` 命令测试，可以不改 hosts。

## 3. 获取 authURL

推荐先跑一键 smoke test：

```bash
bash mock-cas/smoke-test.sh
```

它会自动完成：

- 调用 `/login/oauth/getAuthURL`
- 访问 mock CAS `/sso/login`
- 回调 SSO `/login/oauth/callback`
- 调用 `/login/oauth/getUserInfo`

如果想手动分步测试，按下面步骤执行。

```bash
curl -s \
  -H "Authorization: Bearer test-token" \
  "http://127.0.0.1:8080/login/oauth/getAuthURL?redirect_uri=http%3A%2F%2Ffastgpt.local%2Flogin%2Fcallback&state=local-state"
```

预期返回里有：

```json
{
  "success": true,
  "authURL": "http://mock-cas:18080/sso/login?appid=fastgpt-local&service=..."
}
```

## 4. 跑完整跳转链

把上一步返回的 `authURL` 复制出来，执行：

```bash
curl -i -L \
  --resolve mock-cas:18080:127.0.0.1 \
  --resolve fastgpt-sso:8080:127.0.0.1 \
  "上一步返回的authURL"
```

预期最后会重定向到你传入的 `redirect_uri`，并带上 FastGPT 需要的临时 code：

```text
Location: http://fastgpt.local/login/callback?code=weaver_xxx&state=local-state
```

如果看到 `code=weaver_`，说明：

- SSO 镜像成功跳到 CAS 登录地址。
- mock CAS 成功签发 `ticket`。
- `weaversso.jar` 成功调用 `/sso/serviceValidate` 验票。
- SSO 服务成功从 Session 中读取 CAS Assertion。
- SSO 服务成功生成 FastGPT 使用的临时 `code`。

## 5. 用 code 换用户信息

把上一步 `Location` 里的 `code` 复制出来：

```bash
curl -s \
  -H "Authorization: Bearer test-token" \
  "http://127.0.0.1:8080/login/oauth/getUserInfo?code=替换为code"
```

预期：

```json
{
  "success": true,
  "username": "zhangsan",
  "memberName": "张三",
  "contact": "zhangsan@example.com"
}
```

## 6. mock CAS 可调整的用户字段

在 `docker-compose.mock-cas.yml` 的 `mock-cas.environment` 中修改：

```yaml
MOCK_CAS_USERNAME: zhangsan
MOCK_CAS_LOGINID: zhangsan
MOCK_CAS_LASTNAME: 张三
MOCK_CAS_EMAIL: zhangsan@example.com
MOCK_CAS_MOBILE: "13800000000"
```

这些字段会输出到 CAS XML：

```xml
<cas:attributes>
  <cas:loginid>zhangsan</cas:loginid>
  <cas:lastname>张三</cas:lastname>
  <cas:email>zhangsan@example.com</cas:email>
  <cas:mobile>13800000000</cas:mobile>
</cas:attributes>
```

对应 SSO 镜像环境变量：

```yaml
WEAVER_SSO_ATTR_USERNAME: loginid
WEAVER_SSO_ATTR_MEMBER_NAME: lastname
WEAVER_SSO_ATTR_EMAIL: email
WEAVER_SSO_ATTR_MOBILE: mobile
```

## 7. 常见问题

### 浏览器打不开 `http://mock-cas:18080`

宿主机没有配置 hosts。加：

```text
127.0.0.1 mock-cas fastgpt-sso
```

### 回调时报 `Session中未找到CAS Assertion`

检查：

```bash
docker logs fastgpt-sso-java
docker logs fastgpt-mock-cas
```

重点看 mock CAS 是否收到 `/sso/serviceValidate` 请求，以及 `service` 是否与签发 ticket 时的 `service` 一致。

### 生产环境不要使用这个 mock

`mock-cas` 只用于本地联调，它没有真实登录、签名、会话安全和权限控制。
