# 官网截图用的演示服务器

`site/assets/screens/` 中的截图来自 Android 模拟器上的真实应用界面，连接的是这里构建的两台演示主机，不含真实主机、账号或项目。

- `Dockerfile`：Ubuntu 24.04（`devbox`）或 Debian 12（`build`），账号 `dev` / 密码 `demo`，含 tmux、mosh 和示例项目 `home/`。mosh-server 被限制在发布的 UDP 端口范围内。
- `fake-agent.py`：代替 `claude` / `codex` 的演示程序，按启动目录（`acme-app`、`infra`、`blog`）画出对应的 Agent 界面，并像 `agent-hook.sh` 一样写入 Roamtty 读取的 tmux 选项（运行中 / 等待确认 / 已完成）。语言取自 `~/.demo-lang`（`zh` 或 `en`）。

```sh
cd scripts/site-demo
docker build -t roamtty-demo:devbox .
docker build -t roamtty-demo:build --build-arg BASE=debian:12 --build-arg MOSH_PORTS=60020:60030 .
docker run -d --name roamtty-demo-devbox --hostname devbox -p 127.0.0.1:22300:22 -p 127.0.0.1:60000-60010:60000-60010/udp roamtty-demo:devbox
docker run -d --name roamtty-demo-build --hostname build -p 127.0.0.1:22301:22 -p 127.0.0.1:60020-60030:60020-60030/udp roamtty-demo:build

# 模拟器（userdebug 镜像）里把 Tailscale 风格的地址转到这两台容器
adb -s emulator-5554 root
adb -s emulator-5554 shell 'iptables -t nat -A OUTPUT -d 100.101.10.2 -p tcp --dport 22 -j DNAT --to-destination 10.0.2.2:22300;
  iptables -t nat -A OUTPUT -d 100.101.10.2 -p udp -j DNAT --to-destination 10.0.2.2;
  iptables -t nat -A OUTPUT -d 100.101.10.7 -p tcp --dport 22 -j DNAT --to-destination 10.0.2.2:22301;
  iptables -t nat -A OUTPUT -d 100.101.10.7 -p udp -j DNAT --to-destination 10.0.2.2'
```

用 `-PitermuxApplicationId=com.roamtty.app.demo` 构建的独立包安装到模拟器（不影响已装的 Roamtty），开启系统界面演示模式（时间 9:41、满电量）。添加主机 `devbox` = `dev@100.101.10.2`、`build` = `dev@100.101.10.7`，在 `devbox` 的 `~/acme-app`、`~/blog` 启动 Claude，在 `build` 的 `~/infra` 启动 Codex。截图缩放到 720×1600 并转成 WebP，命名为 `<画面>-<zh|en>.webp`。切换语言时在设置中改应用语言，并把两台容器的 `~/.demo-lang` 改掉后重启各会话里的 Agent。
