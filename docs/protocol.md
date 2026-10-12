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
| 404 | The path is not `/notify`. |
| 405 | The request method is not POST. |
| 413 | The body exceeds the size limit. |
| 429 | The inbox is full. |

Acceptance does not guarantee immediate display. Minecraft must be in a world and notifications must be enabled. The bounded inbox holds at most 32 messages. The popup renderer shows at most three messages at once.

## Connection behavior

The receiver starts and stops with the client. It binds only to loopback and does not expose a remote webhook. Cross-origin browser requests are not supported. The Python adapter ignores system HTTP proxy settings so the token stays on the local connection.

The token authorizes notification delivery only. It does not authorize game commands or other Minecraft actions. Keep the config file private.
