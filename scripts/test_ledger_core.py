#!/usr/bin/env python3
"""Run the Android-independent production policies with JUnit 4 when an SDK is unavailable.
This does not replace the Android Gradle build or device/instrumentation tests.
"""
import argparse
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--java', required=True)
parser.add_argument('--kotlin-home', required=True)
parser.add_argument('--junit', required=True)
parser.add_argument('--hamcrest', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
kotlin = Path(args.kotlin_home)
names = ['LedgerPolicy', 'CapturePolicy', 'ReceiptPolicy', 'MD5HashTable']
sources = [root / f'server/src/main/java/org/ezbook/server/tools/{name}.kt' for name in names]
sources += [root / f'server/src/test/java/org/ezbook/server/tools/{name}Test.kt' for name in names]
classpath = ':'.join([str(kotlin / 'lib/kotlin-stdlib.jar'), args.junit, args.hamcrest])
with tempfile.TemporaryDirectory(prefix='justledger-core-') as output:
    subprocess.run([args.java, '-cp', str(kotlin / 'lib/*'), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', classpath,
                    '-d', output, *map(str, sources)], check=True)
    subprocess.run([args.java, '-cp', f'{output}:{classpath}', 'org.junit.runner.JUnitCore',
                    *[f'org.ezbook.server.tools.{name}Test' for name in names]], check=True)
