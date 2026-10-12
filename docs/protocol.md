# Local notification protocol

MineNotify listens on IPv4 loopback, `127.0.0.1`, using the `port` and `token` from the running Minecraft instance's `config/minenotify.json`. The default port is `47653`.

## Request

`POST /notify`

Required headers:

```http
Authorization: Bearer <token from the local config>
Content-Type: application/json
```

Example body:

```json
{
  "source": "Codex",
  "title": "Task finished",
  "message": "All checks passed."
}
```

The fields are plain text. Source and title are required; message is optional. The source is limited to 64 UTF-16 code units, the title to 128, and the message to 2,048. Most common characters use one unit; emoji can use two. The Python bridge truncates text to these limits. The total request body cannot exceed 16 KiB. There are no links, commands, or server-chat actions in the payload.

## Responses

| Status | Meaning |
| --- | --- |
| 202 | The notification was accepted into the local inbox. |
| 400 | The JSON or notification fields are invalid. |
| 401 | The bearer token is missing or incorrect. |
| 404 | The path is neither `/notify` nor `/status`. |
| 405 | The method is not POST for `/notify` or GET for `/status`. |
| 413 | The body exceeds the size limit. |
| 429 | The inbox is full. |

Acceptance does not guarantee immediate display. Minecraft must be in a world and notifications must be enabled. The bounded inbox holds at most 32 messages. Undelivered notifications expire after five minutes, measured with a monotonic clock. Expired messages do not occupy inbox capacity or appear when you enter a world. The popup renderer shows at most three messages at once.

## Receiver status

`GET /status` requires the same `Authorization` header as notification delivery. It does not require a body or `Content-Type` header.

A successful request returns HTTP 200 with this shape:

```json
{
  "status": "listening",
  "pending": 3,
  "capacity": 32,
  "accepted": 9,
  "expired": 2,
  "uptimeSeconds": 12
}
```

`pending` counts unexpired messages waiting for delivery. `accepted` counts notifications accepted during this receiver's lifetime, including delivered and expired messages. `expired` counts undelivered messages removed after five minutes. `uptimeSeconds` measures elapsed time since the receiver started. Counters reset when the receiver restarts.

Status requests discard expired entries but leave valid notifications in the inbox. The response never includes access tokens, paths, or notification text. All responses use `Cache-Control: no-store` and provide no CORS headers.

The Python helper's `--status` action prints this JSON without sending a notification. An unreachable receiver, incorrect token, or invalid response produces a diagnostic on stderr and exit code 1. Agent hooks retain their quiet exit code 0 behavior when delivery fails.

## Connection behavior

The receiver starts and stops with the client. It binds only to loopback and does not expose a remote webhook. Cross-origin browser requests are not supported. The Python adapter ignores system HTTP proxy settings so the token stays on the local connection.

The token authorizes notification delivery only. It does not authorize game commands or other Minecraft actions. Keep the config file private.
