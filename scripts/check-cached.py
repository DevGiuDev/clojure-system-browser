#!/usr/bin/env python3
"""Run core checks from an existing Maven cache without changing deps.edn.

An older rewrite-clj may be selected explicitly for a compatibility check;
that does not validate the pinned production version.
"""
import argparse
import os
from pathlib import Path
import subprocess
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--cached-rewrite", default="1.3.59")
parser.add_argument("--java", default=os.environ.get("CSB_JAVA", "java"))
parser.add_argument("--main", default="csb.test-runner")
args, remaining = parser.parse_known_args()
root = Path(__file__).resolve().parent.parent
cache = Path(os.environ.get("CSB_M2", str(Path.home() / ".m2/repository")))
coordinates = [
    ("org/clojure/clojure", "clojure", "1.12.6"),
    ("org/clojure/spec.alpha", "spec.alpha", "0.5.238"),
    ("org/clojure/core.specs.alpha", "core.specs.alpha", "0.4.74"),
    ("org/clojure/tools.reader", "tools.reader", "1.6.0"),
    ("rewrite-clj/rewrite-clj", "rewrite-clj", args.cached_rewrite),
]
jars = [cache / group / version / f"{artifact}-{version}.jar"
        for group, artifact, version in coordinates]
missing = [str(p) for p in jars if not p.is_file()]
if missing:
    sys.exit("Missing cached dependencies:\n" + "\n".join(missing))
print(f"Checking Clojure 1.12.6 / rewrite-clj {args.cached_rewrite}", flush=True)
classpath = os.pathsep.join(map(str, [root / "src", root / "test", *jars]))
sys.exit(subprocess.call([args.java, "-cp", classpath, "clojure.main", "-m", args.main, *remaining], cwd=root))
