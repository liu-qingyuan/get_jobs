#!/usr/bin/env python3
"""Report one attributed event to a local task; SUBMITTING must be acknowledged before clicking."""
import argparse
import json
from pathlib import Path
import sys
from urllib.parse import urlsplit
from urllib.request import Request
from urllib.error import HTTPError, URLError


def report(bundle_path, event, message, evidence="", content=""):
    bundle = json.loads(Path(bundle_path).read_text(encoding="utf-8"))
    endpoint = bundle["callbackUrl"]
    url = urlsplit(endpoint)
    if url.scheme != "http" or url.hostname not in {"localhost", "127.0.0.1", "::1"} or url.username or url.password:
        raise ValueError("Task callback must be local HTTP")
    payload = {"type": event, "message": message, "evidence": evidence}
    if content:
        payload["content"] = content
    request = Request(endpoint, data=json.dumps(payload, ensure_ascii=False).encode(), method="POST",
                      headers={"Content-Type": "application/json", "Authorization": "Bearer " + bundle["token"]})
    # Never follow redirects carrying the task credential to another destination.
    import urllib.request
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, request, fp, code, msg, headers, newurl):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with opener.open(request, timeout=15) as response:
        result = json.load(response)
    return {"taskId": result["id"], "state": result["state"]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle")
    parser.add_argument("event", choices=["PROGRESS", "NEEDS_INPUT", "FAILED", "SUBMITTING", "SUBMITTED", "EMPLOYER_STATUS", "DRAFT"])
    parser.add_argument("message")
    parser.add_argument("--evidence", default="")
    parser.add_argument("--content-file", type=Path)
    args = parser.parse_args(argv)
    try:
        content = args.content_file.read_text(encoding="utf-8") if args.content_file else ""
        result = report(args.bundle, args.event, args.message, args.evidence, content)
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (OSError, ValueError, KeyError, HTTPError, URLError):
        print("REPORT_FAILED: stop; do not submit or retry without an acknowledged event", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
