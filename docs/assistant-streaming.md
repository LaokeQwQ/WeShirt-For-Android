# 原生助手流式回答

客户端向同一服务发送一次鉴权 `POST /api/assistant/chat/stream`，请求体仍为 `{ "input": "..." }`，使用 `Accept: text/event-stream`。它不会自动重试 POST，也不会使用 SSE 的 `id`、`retry` 自动重连。

服务器在流开始前可返回正常 HTTP JSON 错误。流开始后使用以下事件；注释行可作心跳：

```text
event: delta
data: {"text":"本次新增文本"}

event: reset
data: {}

event: done
data: {"reply":"完整回答","messages":[],"context":{},"usage":{}}

event: error
data: {"error":{"code":"upstream_error","message":"回答未完成","status":502,"eventId":"evt_example"}}

```

`reset` 是可选事件，用于工具调用轮次切换，清空当前草稿。`done` 必须在保存最终历史后发送；安卓用其中的完整 `messages` 替换本地草稿，而不是自行把流式文本当作已保存记录。

界面收到文本便显示，最多每 50ms 更新一次 Markdown。用户可停止生成；关闭界面、切换账号或服务会取消原生 HTTP 连接。中断后保留可见部分并提示尚未确认保存，保留输入问题，用户可刷新历史确认，不暗中再调用模型。

读取器处理 UTF-8 分包、CRLF、多行 data 与心跳；回答上限 6000 字符，单行上限 512KiB，总流量上限 2MiB。缺少 done、格式异常、HTTP 错误和流内错误均提供状态与事件编号。会话与身份在每次发布内容前重新核对。

测试覆盖真实 HTTPS 按单字节传输中文、首段早于 done、错误和 EOF 不重放、阻塞读取可取消，以及原生界面中途显示和停止后的草稿/历史边界。
