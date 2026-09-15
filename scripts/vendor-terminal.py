"""Import only Termux's Apache-2.0 libraries, excluding JNI/local shell sources."""
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
COMMIT = '4584488513c099f2e98bcfcd00f006d213248729'
UPSTREAM = ROOT / '.tools' / ('termux-app-' + COMMIT)
if any((ROOT / module / 'src').exists() for module in ('terminal-emulator', 'terminal-view')):
    raise SystemExit('Terminal sources already exist. Refusing to overwrite the reviewed SSH adaptations.')
for module in ('terminal-emulator', 'terminal-view'):
    for source in (UPSTREAM / module / 'src').rglob('*'):
        if not source.is_file() or 'jni' in source.parts:
            continue
        if source.name in ('TerminalSession.java', 'JNI.java', 'ByteQueue.java', 'ByteQueueTest.java'):
            continue
        target = ROOT / module / source.relative_to(UPSTREAM / module)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    for name in ('LICENSE', 'LICENSE.md', 'NOTICE', 'NOTICE.md'):
        source = UPSTREAM / module / name
        if source.exists(): shutil.copyfile(source, ROOT / module / name)
print('Imported terminal components at', COMMIT)
