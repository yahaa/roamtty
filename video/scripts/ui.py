import subprocess,sys,xml.etree.ElementTree as ET,os
adb=[os.path.expanduser('~/platform-tools/adb'),'-s','emulator-5554']
subprocess.run(adb+['shell','uiautomator','dump','/sdcard/u.xml'],stdout=subprocess.DEVNULL,check=True)
r=ET.fromstring(subprocess.check_output(adb+['exec-out','cat','/sdcard/u.xml']))
for n in r.iter('node'):
  a=n.attrib
  if a.get('text') or a.get('content-desc'):
    t='<hidden>' if a.get('password')=='true' else a.get('text')
    print(repr(t), repr(a.get('content-desc')), a['bounds'], 'C' if a.get('clickable')=='true' else '')
