# doc-reader MCP 服务器

一个**零外部依赖、单文件**的 MCP 服务器，提供 `read_doc` 工具：抓取线上网页/文档并转成可读的 Markdown 文本。用于在 Claude Code 联网工具（WebFetch/WebSearch）不可用时阅读在线文档。

## 提供的工具

### `read_doc`

抓取一个 URL 并返回可读文本。

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `url` | string | 是 | 完整 URL（http/https） |
| `start_index` | number | 否 | 从该字符偏移开始返回，用于分页读长文档，默认 `0` |
| `max_length` | number | 否 | 单次返回最大字符数，默认 `20000` |
| `raw` | boolean | 否 | `true` 返回未转换的原始 HTML，默认 `false` |

特性：自动解压 gzip/deflate/br、跟随 3xx 重定向（最多 5 跳）、去除 `script/style` 等噪声、把标题/段落/列表/链接/代码块转为 Markdown。返回头部会标注当前字符区间，未读完时给出下一段的 `start_index`。

## 启用方式

服务器已在项目根 `.mcp.json` 注册：

```json
{
  "mcpServers": {
    "doc-reader": {
      "command": "node",
      "args": ["./tools/doc-mcp/index.js"]
    }
  }
}
```

1. 确保本机已安装 **Node.js**（≥ 14），且 `node` 在 PATH 上。
2. 重启 / 重新加载 Claude Code 会话，使 `.mcp.json` 生效。
3. 首次会提示批准 `doc-reader` 服务器，同意后即可调用 `read_doc`。

## 手动冒烟测试

```bash
printf '%s\n' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_doc","arguments":{"url":"https://help.gravity-engine.com/docs/android","max_length":3000}}}' \
  | node tools/doc-mcp/index.js
```

stdout 应返回两条 JSON-RPC 响应，第二条的 `result.content[0].text` 即文档正文。

## 已知限制

- **SPA 站点**：若目标文档为纯前端渲染、首屏 HTML 不含正文，HTTP 抓取拿不到内容。多数文档站为服务端渲染，通常可用。如遇此情况需另加无头浏览器（本期未实现）。
- HTML→Markdown 为轻量正则转换，复杂排版（嵌套表格等）可能不完美，但足以阅读技术文档。
