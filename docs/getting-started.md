# 使用与部署

## 1. 环境要求

推荐使用 Docker 部署后端：

- Docker Engine 24 或更高版本；
- Docker Compose v2；
- Android 8.0 或更高版本的 `arm64-v8a` 设备；
- 从源码构建 App 时需要 Git LFS、JDK 17 和 Android SDK 36。

书籍原文件不会被后端改写。数据库、封面和扫描结果默认保存在 `data/`，书籍默认从 `library/`
只读挂载。

## 2. 获取源码

### 使用预构建镜像

不需要源码即可部署后端。下载部署文件：

```bash
mkdir page-shelf && cd page-shelf
curl -LO https://raw.githubusercontent.com/samworthington39-commits/page-shelf/main/deploy/compose.yaml
curl -Lo .env https://raw.githubusercontent.com/samworthington39-commits/page-shelf/main/deploy/.env.example
mkdir -p library data
docker compose pull
docker compose up -d
```

镜像支持 `linux/amd64` 与 `linux/arm64`：

```bash
docker pull ghcr.io/samworthington39-commits/page-shelf:latest
```

也可以从 [GitHub Releases](https://github.com/samworthington39-commits/page-shelf/releases) 下载 Docker
部署包和 Android APK。Release 与容器镜像均不包含任何图书资源。

### 从源码构建

完整克隆，包括 Android 模型：

```bash
git lfs install
git clone <your-repository-url>
cd <repository-directory>
git lfs pull
```

只部署后端：

```bash
GIT_LFS_SKIP_SMUDGE=1 git clone <your-repository-url>
cd <repository-directory>
```

## 3. 配置后端

Linux/macOS：

```bash
cp .env.example .env
mkdir -p library data
```

PowerShell：

```powershell
Copy-Item .env.example .env
New-Item -ItemType Directory -Force library, data
```

默认配置可以直接启动。首次登录密码为 `112233`，登录后管理界面会要求立即修改。若希望在首次启动前
指定不同的初始密码，可编辑 `.env`：

```dotenv
ADMIN_PASSWORD=你的初始密码
```

会话密钥会自动随机生成，密码哈希和密钥保存在 `data/admin_credentials.json`。不要提交 `.env` 或
`data/`。凭据文件生成后，修改 `.env` 中的初始密码不会覆盖管理界面里设置的新密码。

## 4. 启动与初始化

```bash
docker compose up -d --build
docker compose ps
docker compose logs -f api
```

服务正常时：

```bash
curl http://localhost:8000/health
```

响应示例：

```json
{"status":"ok","api_version":"1.0"}
```

Compose 项目名和容器名均为 `page-shelf`。浏览器打开
`http://<服务器IP>:8000/admin#books`（部署服务器本机可使用 `http://localhost:8000/admin#books`）：

1. 首次使用默认密码 `112233` 登录；
2. 按提示设置至少 8 位的新管理密码，该步骤不能跳过；
3. 登记 `/library`；
4. 创建书架并选择目录；
5. 扫描书籍；
6. 按需修改 TXT/EPUB/MOBI 的章节拆分策略、标题、作者和封面。

支持 `.txt`、`.epub`、`.mobi` 和 `.pdf`，扩展名不区分大小写。PDF 始终保持固定版式，不进行章节拆分
或 OCR。MOBI 支持未加密的可重排电子书：后端会在受限临时目录中解包，提取可用的目录、元数据与封面，
然后清理临时内容。受 DRM 保护的 MOBI 不会被绕过或导入，Print Replica 固定版式请先合法转换为 PDF。
单个 MOBI 文件上限为 256 MiB。

完成首次改密并扫描书籍后，可在浏览器打开网页阅读器：

```text
http://<服务器IP>:8000/reader
```

输入修改后的访问密码即可进入书架。书架标签横向排列并通过点击切换，右上角的“管理”按钮可返回管理
后台；阅读页只使用上下滚动，顶栏提供上一章、目录、下一章按钮，目录从右侧展开。TXT/EPUB/MOBI 滑到章末
会自动续接下一章。纸白、护眼、夜间配色以及宋体、楷体、黑体和字号设置会保存在当前浏览器中；访问
密码不持久化，短期会话只保存在当前标签页的会话存储中。

## 5. 挂载 NAS 或其他目录

不要把个人 NAS 路径直接提交进 `compose.yaml`。在本机创建 `compose.override.yaml`：

```yaml
services:
  api:
    volumes:
      - /path/on/host/novels:/books:ro
    environment:
      STORAGE_ALLOWED_ROOTS: /library,/books
```

Windows Docker Desktop 示例：

```yaml
services:
  api:
    volumes:
      - D:/Books:/books:ro
    environment:
      STORAGE_ALLOWED_ROOTS: /library,/books
```

重新创建容器：

```bash
docker compose up -d --build
```

如果 NAS 文件在容器中显示为 mode `000`，先修复宿主机 ACL。确实无法修复时，可在本地 override 中
按最小权限添加 `DAC_OVERRIDE`，不要把该 capability 设为公开仓库默认值：

```yaml
services:
  api:
    cap_add:
      - DAC_OVERRIDE
```

## 6. Android 连接

先通过网页管理界面完成首次改密。安装 APK 后输入后端地址和修改后的管理密码，地址不要包含
`/admin` 或 `/api/v1` 路径：

```text
192.168.1.10:8000
https://reader.example.com
```

App 的网络规则：

- `10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、localhost 和本地 IPv6 可以使用 HTTP；
- 公网域名必须使用 HTTPS；
- 服务器 API 主版本必须与 App 支持的版本一致。

管理密码只用于换取短期会话。会话过期时 App 会重新认证；正文、Token 和密码不会写入 HTTP 日志。

## 7. HTTPS 与公网访问

公网部署必须在后端前使用 HTTPS 反向代理。代理需要：

- 将请求转发到 `http://127.0.0.1:8000`；
- 保留 `Range`、`If-Range`、`ETag` 等文件下载相关请求头；
- 允许大文件流式响应，避免在代理层完整缓冲 PDF/EPUB；
- 仅开放 HTTPS，不直接暴露不受保护的 8000 端口。

只有在存在独立 Web 前端时才配置精确的 `CORS_ORIGINS`。管理后台与 API 同源时保持为空。

## 8. 备份与更新

至少备份：

```text
data/bookshelf.db
data/covers/
data/admin_credentials.json
.env
```

书籍原文件应由 NAS 或文件系统自己的备份策略保护。更新代码后：

```bash
git pull --ff-only
git lfs pull
docker compose up -d --build
```

更新前先备份 `data/`。不要删除数据库旁的 SQLite `-wal`/`-shm` 文件后再单独复制数据库；应停止容器
后备份整个 `data/`，或使用 SQLite 的一致性备份方式。

## 9. 无 Docker 启动

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -e ".\backend[test]"
Copy-Item .env.example .env
New-Item -ItemType Directory -Force library, data
Set-Location backend
..\.venv\Scripts\python -m uvicorn app.main:app --reload
```

Linux/macOS 将激活路径替换为 `.venv/bin/python`。此方式的相对数据库和书库路径以当前工作目录为准。

## 10. 常见问题

### 忘记管理密码

当前版本不会在日志或界面中显示已修改的密码。恢复最近的 `data/admin_credentials.json` 备份；如果没有
备份，停止容器后移走该文件，再重新启动，系统会根据 `.env` 中的 `ADMIN_PASSWORD` 初始化凭据。
未自定义时初始密码为 `112233`，登录后必须再次修改。旧会话会全部失效。

```bash
docker compose stop api
mv data/admin_credentials.json data/admin_credentials.json.bak
docker compose up -d
```

确认可以登录且书库数据正常后，再妥善处理旧凭据备份。凭据文件包含安全敏感信息，不要公开或提交到
版本库。

### 修改 `.env` 后初始密码没有变化

`ADMIN_PASSWORD` 只在 `data/admin_credentials.json` 不存在时用于初始化。日常改密请使用管理界面“系统维护”
中的“修改管理密码”。

### 后台看不到 NAS 目录

先运行 `docker compose exec api ls -la /books` 验证容器内路径，再检查 `STORAGE_ALLOWED_ROOTS` 和
`STORAGE_EXCLUDED_ROOTS`。后台只能登记容器实际挂载且被授权的路径。

### 手机无法连接

- 手机与服务器处于同一局域网；
- 使用服务器局域网 IP，不要在真机上填写 `localhost`；
- 防火墙允许 8000 端口，或使用反向代理的 443 端口；
- 浏览器访问 `/health` 验证网络链路；
- 公网域名使用有效 HTTPS 证书。

### Android 构建时模型文件异常

运行 `git lfs install` 和 `git lfs pull`。如果 `model-steps-3.onnx` 或
`vocos-16khz-univ.onnx` 只有几行且包含 `version https://git-lfs.github.com/spec/v1`，
当前拿到的是 LFS 指针，不是模型内容。
