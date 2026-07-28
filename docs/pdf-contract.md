# PDF 处理与页码约定

## 不变量

- PDF 是固定版式原始文件，不参与章节体系。
- 后端不提取 PDF 正文、不识别标题、不生成虚拟章节、不执行 OCR。
- `chapters` 表只保存 TXT、EPUB 和 MOBI 数据；PDF 的 `chapter_count` 始终为 `NULL`。
- PDF outline 仅保存在 `books.pdf_navigation_json`，不能转换为 `chapters` 行。
- 加密、损坏或无法读取页数的单个 PDF 必须作为带警告的书籍入库，不能中断整次扫描。

## 页码规则

数据库、Android Room 和进度 API 中统一使用从 0 开始的 `page_index`。Android 界面和
`GET /api/v1/books/{book_id}/pdf-navigation` 的 `page` 使用从 1 开始的页码。

```text
display_page = page_index + 1
progression = display_page / page_count
```

因此 10 页文档的内部索引范围为 `0..9`，第 1 页进度为 `0.1`，第 10 页为 `1.0`。

## 原生书签存储

后端内部格式：

```json
{
  "title": "前言",
  "page_index": 4,
  "children": []
}
```

导航 API 将其转换为客户端页码：

```json
{
  "title": "前言",
  "page": 5,
  "children": []
}
```

## 文件更新与离线副本

服务端扫描时计算 SHA-256 指纹，并把该指纹作为文件接口的强 ETag。Android 下载完成后保存
同一指纹；书籍详情返回的新指纹与本地不一致时，状态变为“服务器文件已更新”，要求重新下载。
已经下载完成的旧副本在离线状态下不会被自动删除。
