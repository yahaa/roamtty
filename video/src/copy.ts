export type Variant = 'review' | 'site';
export type Lang = 'en' | 'zh';

type Copy = {
  eyebrow: string;
  title: string;
  subtitle: string;
  steps: {label: string; title: string; body: string}[];
  outroTitle: string;
  outroBullets: string[];
  outroFooter: string;
  footnote: string;
  stepWord: string;
  speed: (rate: number) => string;
};

const stepsEn = [
  {label: 'Start a session', title: 'Start an AI agent session', body: 'Pick Claude Code, a server and a project folder, then tap Start.'},
  {label: 'Chat with the agent', title: 'Talk to the agent over SSH', body: 'Roamtty opens an interactive SSH session. Claude Code reads the project on your server and replies.'},
  {label: 'Switch apps', title: 'Switch apps, stay connected', body: 'While a session is open, a foreground service keeps the connection alive and shows an ongoing notification.'},
  {label: 'Return and continue', title: 'Tap the notification to return', body: 'The same AI session is still live, so you can ask a follow-up right away.'},
  {label: 'Disconnect all', title: 'Disconnect all ends it', body: 'One tap closes every session. The foreground service stops and its notification disappears.'},
];

const stepsZh = [
  {label: '开始会话', title: '开启 AI Agent 会话', body: '选择 Claude Code、服务器和项目目录，点“开始”。'},
  {label: '与 Agent 对话', title: '通过 SSH 与 Agent 对话', body: 'Roamtty 建立交互式 SSH 会话，Claude Code 在你的服务器上阅读项目并回答。'},
  {label: '切换应用', title: '切到别的应用，连接不断', body: '会话打开期间，前台服务保持连接，并显示常驻通知。'},
  {label: '返回继续', title: '点通知回到会话', body: '还是同一个 AI 会话，可以直接继续追问。'},
  {label: '全部断开', title: '一键全部断开', body: '所有会话关闭，前台服务停止，通知随之消失。'},
];

const withStep3 = (steps: typeof stepsEn, body: string) => steps.map((s, i) => (i === 2 ? {...s, body} : s));
const siteStepsEn = withStep3(stepsEn, 'Your session stays connected in the background, with an ongoing notification to jump back in.');
const siteStepsZh = withStep3(stepsZh, '会话在后台保持连接，常驻通知一点即可回来。');

export const COPY: Record<Variant, Record<Lang, Copy>> = {
  review: {
    en: {
      eyebrow: 'ROAMTTY FOR ANDROID',
      title: 'Remote AI agents on your phone',
      subtitle: 'How Roamtty uses a foreground service to keep your session connected',
      steps: stepsEn,
      outroTitle: 'A foreground service only while you are connected',
      outroBullets: [
        'Starts when you open or reconnect a session',
        'Ongoing notification: return to the session, or Disconnect all',
        'Stops when the last session closes. Nothing starts at boot',
      ],
      outroFooter: 'roamtty.com  ·  Foreground service type: specialUse',
      footnote: 'Real screen recording · Android 16 · Roamtty 0.1.0 (101) · Some waits sped up',
      stepWord: 'STEP',
      speed: (r) => `${r}× speed`,
    },
    zh: {
      eyebrow: 'ROAMTTY 安卓版',
      title: '在手机上远程使用 AI Agent',
      subtitle: 'Roamtty 如何用前台服务保持会话连接',
      steps: stepsZh,
      outroTitle: '只在连接期间运行前台服务',
      outroBullets: ['打开或重连会话时启动', '常驻通知：返回会话，或全部断开', '最后一个会话关闭即停止，不会开机自启'],
      outroFooter: 'roamtty.com  ·  前台服务类型：specialUse',
      footnote: '真实录屏 · Android 16 · Roamtty 0.1.0 (101) · 部分等待已加速',
      stepWord: '步骤',
      speed: (r) => `${r}× 加速`,
    },
  },
  site: {
    en: {
      eyebrow: 'ROAMTTY',
      title: 'Your AI agents, in your pocket',
      subtitle: 'Run Claude Code, Codex or Grok on your own server, right from your phone',
      steps: siteStepsEn,
      outroTitle: 'Your AI agents, in your pocket',
      outroBullets: [
        'Claude Code, Codex and Grok over SSH',
        'tmux keeps long tasks running on the server',
        'No accounts, ads or telemetry',
      ],
      outroFooter: 'roamtty.com',
      footnote: 'Real screen recording · Some waits sped up',
      stepWord: 'STEP',
      speed: (r) => `${r}× speed`,
    },
    zh: {
      eyebrow: 'ROAMTTY',
      title: '把 AI Agent 装进口袋',
      subtitle: '在手机上运行你服务器上的 Claude Code、Codex 和 Grok',
      steps: siteStepsZh,
      outroTitle: '把 AI Agent 装进口袋',
      outroBullets: ['通过 SSH 使用 Claude Code、Codex、Grok', 'tmux 让长任务在服务器上持续运行', '无账号、无广告、无遥测'],
      outroFooter: 'roamtty.com',
      footnote: '真实录屏 · 部分等待已加速',
      stepWord: '步骤',
      speed: (r) => `${r}× 加速`,
    },
  },
};
