# 安全问题报告

One BNU 会处理用户的统一身份认证账号与密码。凡是可能导致凭据、会话或个人数据泄露的问题，
请不要公开提 Issue，改为发送邮件到 joyreverie27@gmail.com，标题注明「One BNU 安全」。

邮件里请包含：受影响的版本、复现步骤或代码位置、可能的影响范围。收到后会尽快回复并在修复版本的
发布说明中致谢（如不希望署名请说明）。

## 范围

- 凭据存储（`core/store/SecureStore.kt`）、会话与 Cookie 处理（`core/net/`）
- 登录流程与加密实现（`core/net/CasClient.kt`、`core/crypto/KingoDes.kt`）
- 内嵌浏览器（`ui/web/WebScreen.kt`）
- 应用内更新的下载与安装（`core/update/`）

学校系统本身的漏洞不在本项目范围内，请直接联系学校信息化部门。

## 已知的设计边界

- 应用不设服务器，所有请求直接发往学校域名；只有「检查更新」会访问 GitHub 的公开接口。
- 教务系统 `zyfw.bnu.edu.cn` 与图书馆站点只提供 HTTP，应用只对确实需要的直连主机放行明文；
  蜂窝网络访问北京教务时优先经学校 OneVPN 的 HTTPS 代理，统一认证站点始终走 HTTPS，重定向途中的协议降级会被强制升回。
- 发布包由同一把密钥签名，证书 SHA-256 见 README；系统安装时会校验签名一致。
