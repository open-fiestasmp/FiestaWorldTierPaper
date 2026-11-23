#!/usr/bin/env python3
import json
import shutil
import sys
from pathlib import Path

args = sys.argv[1:]
config_path = Path(args[args.index("--config") + 1]).resolve(strict=True)
config = json.loads(config_path.read_text(encoding="utf-8"))

attempt_log = Path(config["attempt_log"])
allow_file = Path(config["allow_file"])
backup_region = Path(config["backup_region"])
target_region = Path(config["target_region"])
marker = Path(config["marker"])

for path in (attempt_log, allow_file, backup_region, target_region, marker):
    if not path.resolve().is_relative_to("/tmp"):
        raise SystemExit("test restore paths must remain under /tmp")

with attempt_log.open("a", encoding="utf-8") as stream:
    stream.write("attempt\n")

if not allow_file.is_file():
    raise SystemExit(1)

shutil.copyfile(backup_region, target_region)
marker.unlink()
