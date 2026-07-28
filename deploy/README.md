# 页架 Page Shelf - Docker 部署 / Docker Deployment

本部署包只包含后端部署配置，不包含任何图书资源。请把自己拥有或有权使用的 TXT、EPUB、MOBI、PDF 文件放入
`library/`，或修改 `.env` 中的 `PAGE_SHELF_LIBRARY_DIR` 指向 NAS 图书目录。

This package contains backend deployment configuration only. It does not contain any books. Point
`PAGE_SHELF_LIBRARY_DIR` to local book files that you own or are authorized to use.

## 启动 / Start

```bash
cp .env.example .env
mkdir -p library data
docker compose pull
docker compose up -d
docker compose ps
```

直接拉取镜像 / Pull the image directly:

```bash
docker pull ghcr.io/samworthington39-commits/page-shelf:latest
```

固定版本可将 `.env` 中的 `PAGE_SHELF_VERSION` 改为 Release 标签，例如 `v1.0.8`。

## 首次登录 / First Login

| 界面 / UI | URL | 用途 / Purpose |
| --- | --- | --- |
| 管理后台 / Admin UI | `http://<服务器IP>:8000/admin` | 改密、书架和系统管理 / Password, shelves and system management |
| 网页书架与阅读器 / Web reader | `http://<服务器IP>:8000/reader` | 浏览与阅读 / Browse and read books |
| Swagger 文档 / Swagger docs | `http://<服务器IP>:8000/docs` | 仅在 `ENABLE_API_DOCS=true` 时开放 / Only when enabled |
| ReDoc 文档 / ReDoc docs | `http://<服务器IP>:8000/redoc` | 仅在 `ENABLE_API_DOCS=true` 时开放 / Only when enabled |

其他地址 / Other addresses:

- Android 服务器地址 / App server URL: `http://<服务器IP>:8000`
- 健康检查接口 / Health endpoint: `http://<服务器IP>:8000/health`
- 默认密码 / Default password: `112233`

首次登录必须立即把默认密码修改为至少 8 位的新密码。App 地址不要带 `/admin` 或 `/api/v1`。

The default password must be changed immediately on first login. Do not append `/admin` or `/api/v1` to the
server URL used by the Android app.

MOBI support is limited to unencrypted, reflowable `.mobi` files. DRM-protected files are not bypassed or imported,
and Print Replica MOBI files should be converted to PDF using a lawful workflow.

## 更新 / Update

```bash
docker compose pull
docker compose up -d
```

更新前请备份 `data/`。书籍目录以只读方式挂载，页架不会改写原始图书文件。
