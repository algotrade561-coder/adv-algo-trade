#!/usr/bin/env python3
"""
parquet_integrity.py — batch parquet integrity checker for s3-archive-retain.sh.

Why this exists
---------------
On 2026-08-20 MicrostructureParquetRoller died mid-write and left three
TRUNCATED parquet files (valid "PAR1" header, no closing footer). The retention
script copied them to S3, its archive gate only asked "does the prefix exist?",
and the raw source tape was then deleted. That trading day is gone.

The fix needs a real integrity check in three places (pre-sync, post-sync, and
the delete gate). Doing that in bash costs one `aws` CLI process per file —
~1s of interpreter startup each, ~240 files, ~4 minutes per cron run. This does
the whole sweep in ONE process with a thread pool: seconds instead of minutes.

A parquet file is well-formed iff it opens with PAR1 and closes with PAR1. The
footer is what a truncated writer never gets to write, so the tail check is the
one that actually separates good from broken. Only 4 bytes are fetched per S3
object (suffix range), so this is cheap regardless of file size.

Modes
-----
  local  <root>                  verify every *.parquet under <root>
  s3     <bucket> <prefix>       verify every *.parquet under s3://bucket/prefix
  pair   <bucket> <root> <prefix>
                                 verify local files AND their S3 counterparts,
                                 reporting which side is bad

Output: one line per bad file on stdout, prefixed BAD_LOCAL / BAD_S3 / MISSING_S3.
Exit 0 = everything intact, 1 = at least one problem, 2 = usage/credential error.
"""
import sys
import os
import concurrent.futures as cf

MAGIC = b"PAR1"
WORKERS = 16


def local_ok(path):
    """True if the file opens and closes with the parquet magic."""
    try:
        size = os.path.getsize(path)
        if size < 8:
            return False
        with open(path, "rb") as fh:
            if fh.read(4) != MAGIC:
                return False
            fh.seek(-4, os.SEEK_END)
            return fh.read(4) == MAGIC
    except OSError:
        return False


def walk_local(root):
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if name.endswith(".parquet"):
                yield os.path.join(dirpath, name)


def s3_client(region):
    import boto3
    return boto3.client("s3", region_name=region)


def s3_ok(client, bucket, key):
    """Fetch only the last 4 bytes — cheap even for a 100 MB object.

    Returns 'ok', 'bad' (present but truncated), or 'missing'.
    """
    try:
        body = client.get_object(Bucket=bucket, Key=key, Range="bytes=-4")["Body"].read()
        return "ok" if body == MAGIC else "bad"
    except Exception as exc:  # NoSuchKey, AccessDenied, transient — all mean "not verified"
        return "missing" if "NoSuchKey" in type(exc).__name__ or "404" in str(exc) else "bad"


def list_s3_parquet(client, bucket, prefix):
    keys = []
    for page in client.get_paginator("list_objects_v2").paginate(Bucket=bucket, Prefix=prefix):
        for obj in page.get("Contents", []):
            if obj["Key"].endswith(".parquet"):
                keys.append(obj["Key"])
    return keys


def main(argv):
    if len(argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    mode = argv[1]
    region = os.environ.get("AWS_REGION", "ap-south-1")
    bad = 0
    checked = 0

    if mode == "local":
        root = argv[2]
        if not os.path.isdir(root):
            return 0
        for path in walk_local(root):
            checked += 1
            if not local_ok(path):
                bad += 1
                print(f"BAD_LOCAL\t{path}")

    elif mode == "s3":
        bucket, prefix = argv[2], argv[3]
        client = s3_client(region)
        keys = list_s3_parquet(client, bucket, prefix)
        with cf.ThreadPoolExecutor(WORKERS) as pool:
            for key, verdict in zip(keys, pool.map(lambda k: s3_ok(client, bucket, k), keys)):
                checked += 1
                if verdict != "ok":
                    bad += 1
                    print(f"{'MISSING_S3' if verdict == 'missing' else 'BAD_S3'}\t{key}")

    elif mode == "pair":
        bucket, root, prefix = argv[2], argv[3], argv[4]
        if not os.path.isdir(root):
            return 0
        paths = list(walk_local(root))
        client = s3_client(region)

        def check(path):
            if not local_ok(path):
                return ("BAD_LOCAL", path)
            rel = os.path.relpath(path, root)
            key = f"{prefix.rstrip('/')}/{rel}"
            verdict = s3_ok(client, bucket, key)
            if verdict == "ok":
                return None
            return ("MISSING_S3" if verdict == "missing" else "BAD_S3", key)

        with cf.ThreadPoolExecutor(WORKERS) as pool:
            for result in pool.map(check, paths):
                checked += 1
                if result:
                    bad += 1
                    print(f"{result[0]}\t{result[1]}")
    else:
        print(f"unknown mode: {mode}", file=sys.stderr)
        return 2

    print(f"# checked={checked} bad={bad}", file=sys.stderr)
    return 1 if bad else 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception as exc:  # never let the checker itself kill the cron job
        print(f"# integrity-checker error: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(2)
