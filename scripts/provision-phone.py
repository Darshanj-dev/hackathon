#!/usr/bin/env python3
"""Developer install: select one authorized phone, install APK, provision verified local AI, launch."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import sys

PACKAGE='com.findback.app'
SHA='181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c'
SIZE=2588147712
MODEL='no_backup/models/gemma-4-E2B-it.litertlm'
PARTIAL=MODEL+'.partial'

def select_device(output, requested=None):
    devices=[line.split()[0] for line in output.splitlines() if len(line.split())==2 and line.split()[1]=='device']
    if requested:
        if requested not in devices:raise RuntimeError('Selected Android device is not connected and authorized.')
        return requested
    if len(devices)!=1:raise RuntimeError('Connect one authorized Android phone, or set ANDROID_SERIAL to choose one.')
    return devices[0]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb',required=True);parser.add_argument('--apk',type=Path,required=True);parser.add_argument('--model',type=Path,required=True)
    args=parser.parse_args()
    if not args.apk.is_file() or not args.model.is_file():raise RuntimeError('Build outputs or verified installation data are missing.')
    if args.model.stat().st_size!=SIZE:raise RuntimeError('Installation data has the wrong size.')
    digest=hashlib.sha256()
    with args.model.open('rb') as stream:
        for chunk in iter(lambda:stream.read(8*1024*1024),b''):digest.update(chunk)
    if digest.hexdigest()!=SHA:raise RuntimeError('Installation data checksum mismatch.')
    devices=subprocess.check_output([args.adb,'devices'],text=True)
    serial=select_device(devices,os.environ.get('ANDROID_SERIAL'))
    adb=[args.adb,'-s',serial]
    def run(*parts,**kwargs):return subprocess.run(adb+list(parts),check=True,**kwargs)
    run('install','-r',str(args.apk))
    run('shell','am','force-stop',PACKAGE)
    run('shell','run-as',PACKAGE,'mkdir','-p','no_backup/models')
    existing=subprocess.run(adb+['shell','run-as',PACKAGE,'sha256sum',MODEL],capture_output=True,text=True)
    if existing.returncode!=0 or existing.stdout.split()[0]!=SHA:
        print('Installing offline assistance on the phone. Keep USB connected.',flush=True)
        command=f"run-as {PACKAGE} sh -c 'cat > {PARTIAL}'"
        with args.model.open('rb') as stream:run('shell','-T',command,stdin=stream)
        verified=subprocess.check_output(adb+['shell','run-as',PACKAGE,'sha256sum',PARTIAL],text=True)
        if verified.split()[0]!=SHA:
            run('shell','run-as',PACKAGE,'rm','-f',PARTIAL)
            raise RuntimeError('Phone checksum mismatch. The assistant was not installed.')
        run('shell','run-as',PACKAGE,'mv',PARTIAL,MODEL)
    print('Offline assistance installed and verified. Starting Saathi.',flush=True)
    run('shell','am','start','-n',PACKAGE+'/.MainActivity')

if __name__=='__main__':
    try:main()
    except (RuntimeError,subprocess.CalledProcessError,OSError) as error:
        print('Developer installation failed: '+str(error),file=sys.stderr);sys.exit(1)
