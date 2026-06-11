#!/usr/bin/env node
/*
 * doc-reader MCP 服务器
 * ---------------------------------------------------------------------------
 * 零外部依赖、单文件实现。只用 Node.js 内置模块。
 * 通过 stdio 传输的 JSON-RPC 2.0（按行分隔）与 Claude Code 通信，
 * 提供一个 read_doc 工具：抓取线上网页/文档并转成可读的 Markdown 文本。
 *
 * 用法：node index.js   （由 MCP 客户端以子进程方式启动，不直接手动跑）
 */

'use strict';

const http = require('http');
const https = require('https');
const zlib = require('zlib');
const { URL } = require('url');

const PROTOCOL_VERSION = '2024-11-05';
const SERVER_NAME = 'doc-reader';
const SERVER_VERSION = '1.0.0';

const DEFAULT_MAX_LENGTH = 20000;
const MAX_REDIRECTS = 5;
const REQUEST_TIMEOUT_MS = 20000;

// ---------------------------------------------------------------------------
// stderr 日志（绝不写 stdout，stdout 仅用于协议）
// ---------------------------------------------------------------------------
function log(...args) {
  process.stderr.write('[doc-reader] ' + args.join(' ') + '\n');
}

// ---------------------------------------------------------------------------
// HTTP(S) 抓取：支持 gzip/deflate/br 解压、重定向、超时
// ---------------------------------------------------------------------------
function httpGet(targetUrl, redirectsLeft) {
  return new Promise((resolve, reject) => {
    let parsed;
    try {
      parsed = new URL(targetUrl);
    } catch (e) {
      reject(new Error('非法 URL: ' + targetUrl));
      return;
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      reject(new Error('仅支持 http/https 协议: ' + parsed.protocol));
      return;
    }

    const lib = parsed.protocol === 'https:' ? https : http;
    const options = {
      method: 'GET',
      headers: {
        'User-Agent':
          'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
          '(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
        'Accept':
          'text/html,application/xhtml+xml,application/xml;q=0.9,' +
          'application/json;q=0.9,text/plain;q=0.8,*/*;q=0.7',
        'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
        'Accept-Encoding': 'gzip, deflate, br',
      },
    };

    const req = lib.request(targetUrl, options, (res) => {
      const status = res.statusCode || 0;

      // 处理重定向
      if (status >= 300 && status < 400 && res.headers.location) {
        res.resume(); // 丢弃 body
        if (redirectsLeft <= 0) {
          reject(new Error('重定向次数过多'));
          return;
        }
        const next = new URL(res.headers.location, targetUrl).toString();
        log('redirect ' + status + ' -> ' + next);
        resolve(httpGet(next, redirectsLeft - 1));
        return;
      }

      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        let buf = Buffer.concat(chunks);
        const encoding = (res.headers['content-encoding'] || '').toLowerCase();
        try {
          if (encoding === 'gzip') buf = zlib.gunzipSync(buf);
          else if (encoding === 'deflate') buf = zlib.inflateSync(buf);
          else if (encoding === 'br') buf = zlib.brotliDecompressSync(buf);
        } catch (e) {
          // 解压失败则按原始字节处理
          log('解压失败(' + encoding + '): ' + e.message);
        }
        resolve({
          status,
          headers: res.headers,
          body: buf.toString('utf8'),
          finalUrl: targetUrl,
        });
      });
    });

    req.on('error', (e) => reject(e));
    req.setTimeout(REQUEST_TIMEOUT_MS, () => {
      req.destroy(new Error('请求超时(' + REQUEST_TIMEOUT_MS + 'ms)'));
    });
    req.end();
  });
}

// ---------------------------------------------------------------------------
// HTML -> Markdown 轻量转换（正则实现，无第三方库）
// ---------------------------------------------------------------------------
function decodeEntities(str) {
  const named = {
    '&nbsp;': ' ',
    '&amp;': '&',
    '&lt;': '<',
    '&gt;': '>',
    '&quot;': '"',
    '&#39;': "'",
    '&apos;': "'",
    '&middot;': '·',
    '&hellip;': '…',
    '&mdash;': '—',
    '&ndash;': '–',
    '&copy;': '©',
    '&reg;': '®',
  };
  return str
    .replace(/&[a-zA-Z]+;/g, (m) => (named[m] !== undefined ? named[m] : m))
    .replace(/&#(\d+);/g, (_, n) => {
      try { return String.fromCodePoint(parseInt(n, 10)); } catch (e) { return _; }
    })
    .replace(/&#x([0-9a-fA-F]+);/g, (_, n) => {
      try { return String.fromCodePoint(parseInt(n, 16)); } catch (e) { return _; }
    });
}

function htmlToMarkdown(html) {
  let s = html;

  // 只取 <body>（若有），减少噪声
  const bodyMatch = s.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
  if (bodyMatch) s = bodyMatch[1];

  // 剥离不可见/无关元素
  s = s
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<style[\s\S]*?<\/style>/gi, '')
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, '')
    .replace(/<svg[\s\S]*?<\/svg>/gi, '')
    .replace(/<head[\s\S]*?<\/head>/gi, '')
    .replace(/<!--[\s\S]*?-->/g, '');

  // 代码块 <pre> -> ``` 围栏（先于其它处理，保留内部换行）
  s = s.replace(/<pre[^>]*>([\s\S]*?)<\/pre>/gi, (_, inner) => {
    const code = inner
      .replace(/<\/?(code|span|div)[^>]*>/gi, '')
      .replace(/<br\s*\/?>/gi, '\n');
    return '\n```\n' + decodeEntities(stripTags(code)).replace(/\n{3,}/g, '\n\n').trim() + '\n```\n';
  });

  // 行内代码
  s = s.replace(/<code[^>]*>([\s\S]*?)<\/code>/gi, (_, inner) => '`' + stripTags(inner) + '`');

  // 标题
  for (let i = 1; i <= 6; i++) {
    const re = new RegExp('<h' + i + '[^>]*>([\\s\\S]*?)<\\/h' + i + '>', 'gi');
    s = s.replace(re, (_, t) => '\n\n' + '#'.repeat(i) + ' ' + stripTags(t).trim() + '\n\n');
  }

  // 链接
  s = s.replace(/<a\b[^>]*href=["']([^"']*)["'][^>]*>([\s\S]*?)<\/a>/gi, (_, href, txt) => {
    const label = stripTags(txt).trim();
    if (!label) return '';
    if (!href || href.startsWith('#') || href.startsWith('javascript:')) return label;
    return '[' + label + '](' + href + ')';
  });

  // 列表项
  s = s.replace(/<li[^>]*>([\s\S]*?)<\/li>/gi, (_, t) => '\n- ' + stripTags(t).trim());

  // 表格行/单元格（简易）
  s = s.replace(/<\/tr>/gi, '\n');
  s = s.replace(/<(td|th)[^>]*>([\s\S]*?)<\/(td|th)>/gi, (_, _tag, t) => stripTags(t).trim() + ' | ');

  // 段落与换行
  s = s
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/p>/gi, '\n\n')
    .replace(/<\/(div|section|article|header|footer|tr|ul|ol|table)>/gi, '\n');

  // 去掉剩余所有标签
  s = stripTags(s);

  // 实体解码
  s = decodeEntities(s);

  // 清理空白：每行 trim 右侧、压缩 3+ 空行为 2
  s = s
    .split('\n')
    .map((line) => line.replace(/[ \t]+$/g, ''))
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .replace(/[ \t]{2,}/g, ' ')
    .trim();

  return s;
}

function stripTags(str) {
  return str.replace(/<[^>]+>/g, '');
}

// ---------------------------------------------------------------------------
// read_doc 工具实现
// ---------------------------------------------------------------------------
async function readDoc(args) {
  const url = args && args.url;
  if (!url || typeof url !== 'string') {
    return { isError: true, text: 'read_doc 需要字符串参数 url' };
  }
  const startIndex = Math.max(0, parseInt(args.start_index, 10) || 0);
  const maxLength =
    args.max_length && parseInt(args.max_length, 10) > 0
      ? parseInt(args.max_length, 10)
      : DEFAULT_MAX_LENGTH;
  const raw = args.raw === true;

  let res;
  try {
    res = await httpGet(url, MAX_REDIRECTS);
  } catch (e) {
    return { isError: true, text: '抓取失败: ' + e.message };
  }

  if (res.status < 200 || res.status >= 300) {
    return {
      isError: true,
      text: 'HTTP ' + res.status + ' 抓取失败: ' + url,
    };
  }

  const contentType = (res.headers['content-type'] || '').toLowerCase();
  let content;

  if (raw) {
    content = res.body;
  } else if (contentType.includes('application/json')) {
    try {
      content = JSON.stringify(JSON.parse(res.body), null, 2);
    } catch (e) {
      content = res.body;
    }
  } else if (
    contentType.includes('text/html') ||
    contentType.includes('xml') ||
    res.body.trimStart().startsWith('<')
  ) {
    content = htmlToMarkdown(res.body);
  } else {
    // 纯文本或未知：原样返回
    content = res.body;
  }

  const total = content.length;
  const slice = content.slice(startIndex, startIndex + maxLength);
  const nextIndex = startIndex + slice.length;
  const hasMore = nextIndex < total;

  let header = 'URL: ' + url + '\n';
  header += '字符: ' + startIndex + '-' + nextIndex + ' / 共 ' + total + '\n';
  if (hasMore) {
    header += '（内容未读完，下一段请用 start_index=' + nextIndex + '）\n';
  }
  header += '\n----------\n\n';

  return { isError: false, text: header + slice };
}

// ---------------------------------------------------------------------------
// MCP 协议处理
// ---------------------------------------------------------------------------
const TOOL_DEFINITION = {
  name: 'read_doc',
  description:
    '抓取一个线上网页/文档(URL)并返回可读的 Markdown 文本。' +
    '自动去除 HTML 标签、解压 gzip、跟随重定向。' +
    '长文档可用 start_index 分页继续读取。适合阅读在线技术文档。',
  inputSchema: {
    type: 'object',
    properties: {
      url: { type: 'string', description: '要抓取的完整 URL（http/https）' },
      start_index: {
        type: 'number',
        description: '从该字符偏移开始返回，用于分页继续读取长文档，默认 0',
      },
      max_length: {
        type: 'number',
        description: '单次返回的最大字符数，默认 20000',
      },
      raw: {
        type: 'boolean',
        description: 'true 时返回未经转换的原始 HTML，默认 false',
      },
    },
    required: ['url'],
  },
};

function sendMessage(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n');
}

function sendResult(id, result) {
  sendMessage({ jsonrpc: '2.0', id, result });
}

function sendError(id, code, message) {
  sendMessage({ jsonrpc: '2.0', id, error: { code, message } });
}

async function handleMessage(msg) {
  const { id, method, params } = msg;

  // 通知（无 id）不需要响应
  const isNotification = id === undefined || id === null;

  switch (method) {
    case 'initialize':
      sendResult(id, {
        protocolVersion: PROTOCOL_VERSION,
        capabilities: { tools: {} },
        serverInfo: { name: SERVER_NAME, version: SERVER_VERSION },
      });
      return;

    case 'notifications/initialized':
    case 'initialized':
      // 客户端就绪通知，无需响应
      return;

    case 'ping':
      if (!isNotification) sendResult(id, {});
      return;

    case 'tools/list':
      sendResult(id, { tools: [TOOL_DEFINITION] });
      return;

    case 'tools/call': {
      const name = params && params.name;
      const args = (params && params.arguments) || {};
      if (name !== 'read_doc') {
        sendError(id, -32602, '未知工具: ' + name);
        return;
      }
      try {
        const out = await readDoc(args);
        sendResult(id, {
          content: [{ type: 'text', text: out.text }],
          isError: out.isError === true,
        });
      } catch (e) {
        sendResult(id, {
          content: [{ type: 'text', text: '内部错误: ' + (e && e.message) }],
          isError: true,
        });
      }
      return;
    }

    default:
      if (!isNotification) sendError(id, -32601, '方法未实现: ' + method);
      return;
  }
}

// ---------------------------------------------------------------------------
// stdin 读取：按行分隔的 JSON-RPC
// ---------------------------------------------------------------------------
function main() {
  let buffer = '';
  let pending = 0;
  let stdinEnded = false;

  // stdin 关闭后，等所有在途请求处理完再退出，避免丢失响应
  function maybeExit() {
    if (stdinEnded && pending === 0) process.exit(0);
  }

  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (chunk) => {
    buffer += chunk;
    let idx;
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).trim();
      buffer = buffer.slice(idx + 1);
      if (!line) continue;
      let msg;
      try {
        msg = JSON.parse(line);
      } catch (e) {
        log('JSON 解析失败: ' + e.message);
        continue;
      }
      pending++;
      Promise.resolve(handleMessage(msg))
        .catch((e) => log('处理消息异常: ' + (e && e.message)))
        .finally(() => {
          pending--;
          maybeExit();
        });
    }
  });
  process.stdin.on('end', () => {
    stdinEnded = true;
    maybeExit();
  });
  log(SERVER_NAME + ' v' + SERVER_VERSION + ' 已启动 (stdio)');
}

main();
