# 项目结构与数据边界

```text
backend/app/
  routers/auth.py              移动端 Bearer 登录与会话验证
  routers/books.py             书籍、目录、单章、封面与原始文件
  routers/progress.py          指定设备进度与跨设备最新进度
  routers/admin.py             仅浏览器使用的后端管理接口
  services/                    TXT/EPUB/PDF 解析、扫描与文件流

android/app/src/main/java/com/example/bookshelf/
  data/remote/                 Retrofit API 和动态服务器适配
  data/local/                  Room 书籍、下载、章节、进度和同步队列
  data/repository/             Auth、Book、Download、Progress、TextReader 仓库
  data/settings/               Keystore 凭证、服务器配置、阅读设置
  worker/                      整书下载和离线进度重试
  ui/library/                  书架、搜索、缓存/错误/空状态
  ui/reader/                   TXT/EPUB 与 PDF 独立阅读器
  ui/manage/                   App 本地下载、缓存和默认阅读设置
```

## 认证

Android 只在登录请求中提交管理密码。后端验证后签发 HMAC 短期会话；普通阅读请求使用
`Authorization: Bearer`。Android Keystore 加密保存密码和会话，以便启动和后台任务在会话过期后
自动续登。OkHttp 只启用 BASIC 日志，不记录请求头、密码、Token 或正文。

## 章节窗口

TXT/EPUB 的章节划分发生在后端。每本书在 `books` 表中保存独立的 `chapter_split_mode`、配置与拆分
修订号；后台按书架列出书籍，修改策略后只重新解析目标书。阅读顺序始终使用源文件出现顺序，解析
出的卷号、章号、子序号与上中下后缀只用于元数据和校验。章节保存原始标题与标准化标题，移动端
目录默认展示原始标题。策略变化会提升内容版本，使 App 放弃旧章节缓存。PDF 不提供任何拆分设置。

目录元数据可以完整加载，正文不可以。`TextReaderRepository` 的内存缓存由
`chapterWindowRange(current, count, radius = 5)` 控制，只保留当前章前后各 5 章。当前章显示后，
按“下一章、上一章、下二章、上二章……”顺序预取；相同请求共享 Deferred，快速切章会取消窗口外
的失效请求。

在线读到的章节写入 `chapter_cache` 临时缓存。主动整书下载会保存原始 TXT/EPUB，并逐章写入同一
表且标记 `isPermanent = true`。清理临时缓存不会删除永久章节或进度。

## PDF

PDF 不进入章节表。App 下载原始 PDF 到私有目录后交给 Android `PdfRenderer`，渲染缓存最多 11
页，并在翻页时只保留当前页前后 5 页。数据库、API 和内部状态使用从 0 开始的 `page_index`，UI
显示从 1 开始的页码。

## 进度与同步

`reading_progress` 使用统一模型保存格式、章节 ID/索引/标题、章节比例、字符偏移、段落索引、PDF
页码与页内偏移、更新时间、设备 ID 和内容指纹。阅读器以 700ms 防抖保存，切章、退页和进入后台
会立即落盘。

上传失败时 `progress_sync_queue` 以 `bookId` 为主键 upsert，所以同一本书只保留最新任务。
WorkManager 在联网后执行同步；失败采用上限 6 小时的指数退避。打开书时对比本机和后端最新记录：
同章小于 1% 的排版漂移直接选择较新记录，跨章、PDF 相差超过 2 页或内容指纹变化会显示冲突选择。

## 后端存储边界

后端从 Docker 授权挂载或 `STORAGE_ALLOWED_ROOTS` 得到可用范围，网页管理后台只能在范围内登记
书架。移动端没有目录扫描、上传、文件管理或用户管理入口。移除后端书架登记或删除手机本地下载
都不会删除 NAS 原始文件。
