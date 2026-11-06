import type {Lang} from './copy';

type SiteCopy = {
  title: string;
  subtitle: string;
  steps: {title: string; body: string}[];
  outroTitle: string;
  outroBullets: string[];
  footnote: string;
  speed: (rate: number) => string;
};

export const SITE_COPY: Record<Lang, SiteCopy> = {
  en: {
    title: 'Claude Code and your desktop, in your pocket',
    subtitle: 'Run Claude Code on your own computer, then check the result on its real desktop',
    steps: [
      {title: 'Every session at a glance', body: 'Grouped by what needs you. Pick an agent, a host and a folder to start another.'},
      {title: 'Give Claude Code a task', body: 'A full terminal built for touch, connected over SSH or mosh.'},
      {title: 'Put the phone away', body: 'When Claude needs you, a notification says so. Tap it to see exactly what it wants to change.'},
      {title: 'Approve from the card', body: 'One tap on Allow, without opening the terminal. The card follows the work until it is done.'},
      {title: 'See the result on the desktop', body: 'The same card opens that computer’s desktop. Try the new dark mode right there.'},
      {title: 'Scroll with two fingers', body: 'Swiping scrolls the page under your fingers, with inertia, just like a trackpad.'},
      {title: 'Switch windows, show the desktop', body: 'All windows and Show desktop are one tap away.'},
      {title: 'Turn the phone', body: 'Landscape gives the desktop and the terminal far more room.'},
      {title: 'Windows too', body: 'The same desktop on Windows, along with macOS and Linux.'},
    ],
    outroTitle: 'Your agents and your desktop, wherever you are',
    outroBullets: [
      'Sessions keep running in tmux when the phone sleeps',
      'Notifications when an agent needs you or finishes',
      'Remote desktop for Linux, macOS and Windows',
      'No accounts, ads or telemetry',
    ],
    footnote: 'Screen recording of the Android app · Some waits sped up',
    speed: (r) => `${r}× speed`,
  },
  zh: {
    title: '把 Claude Code 和电脑桌面装进口袋',
    subtitle: '在自己的电脑上运行 Claude Code，再到真实的桌面上看结果',
    steps: [
      {title: '所有会话一目了然', body: '按是否需要你处理分组。再开一个，只需选 Agent、主机和文件夹。'},
      {title: '给 Claude Code 派任务', body: '为触屏设计的完整终端，通过 SSH 或 mosh 连接。'},
      {title: '放下手机，等通知', body: 'Claude 需要你时会发通知。点开就能看到它具体要改什么。'},
      {title: '在卡片上批准', body: '点一下“允许”，不用打开终端。卡片跟着任务走，直到完成。'},
      {title: '到桌面上看结果', body: '同一张卡片能打开这台电脑的桌面，当场试试新做的暗色模式。'},
      {title: '双指上下滑动', body: '滑动就能滚动手指下的页面，带惯性，和用触控板一样。'},
      {title: '切换窗口，回到桌面', body: '“所有窗口”和“显示桌面”都只要点一下。'},
      {title: '把手机横过来', body: '横屏时，桌面和终端都有更大的空间。'},
      {title: 'Windows 也一样', body: 'Windows 上是同样的桌面体验，macOS 和 Linux 也支持。'},
    ],
    outroTitle: '你的 Agent 和桌面，走到哪带到哪',
    outroBullets: [
      '手机锁屏后，会话继续在 tmux 里运行',
      'Agent 需要你或完成任务时发通知',
      '远程桌面支持 Linux、macOS 和 Windows',
      '无账号、无广告、无遥测',
    ],
    footnote: 'Android 版录屏 · 部分等待已加速',
    speed: (r) => `${r}× 加速`,
  },
};
