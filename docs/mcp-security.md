# MCP 安全边界

MCP 解决的是能力发现和跨进程工具协议，不自动解决鉴权与业务授权。本项目采用客户端与服务端双层防护。

## 客户端 Tool Gateway

- 从当前 Incident 填充 `deviceRef` 和 `correlationRef`；
- 按冻结的设备 Profile 生成工具白名单；
- 检查 AgentRun 租约和工具调用预算；
- 把返回事实写入 Evidence Ledger 和工具审计；
- 禁止模型直接获得 MCP Client。

## 服务端校验

- Bearer Token 与服务角色绑定；
- `actorRole` 不能仅相信 Tool 参数；
- 关联键必须符合 `MONITORING` 或 `BATTERY` 契约；
- 关联键内设备必须与请求设备一致；
- 不接受任意患者 ID、设备 ID 或任意时间范围查询；
- 所有公开工具只读。

客户端首次调用时检查服务器名称、所需 Tools 和版本化 Contract Resource。缺少任何能力都会显式失败；MCP 故障不会自动回退 HTTP，避免绕过已经声明的安全边界。

