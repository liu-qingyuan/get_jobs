#!/usr/bin/env python3
"""Local Feature records: install, list, check exact Git snapshots, explicitly review."""
from __future__ import annotations

import argparse
import fnmatch
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shlex
import stat
import subprocess
import sys
import tempfile

CONTROL = '.feature-docs'
CONFIG = CONTROL + '/config.json'
REGULAR = ('100644', '100755')
ASSETS = {CONTROL + '/' + p for p in ('run', 'hooks/pre-commit', 'hooks/pre-push', 'tool.py', '.gitattributes')}


class GateError(Exception):
    """A missing, conflicting, invalid, or stale project record."""


def git(repo, *args, input=None):
    result = subprocess.run(['git', '-C', str(repo), *args], input=input,
                            capture_output=True, timeout=30)
    if result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', errors='replace').strip())
    return result.stdout


def safe_path(path):
    return (isinstance(path, str) and bool(path) and not path.startswith('/')
            and not any(c in path for c in ('\\', '\n', '\r', '\0'))
            and not any(p in ('', '.', '..', '.git') for p in path.split('/')))


def in_scope(path, patterns):
    return any(fnmatch.fnmatchcase(path, p) or (p.endswith('/**') and path == p[:-3])
               for p in patterns)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise GateError('DUPLICATE_FIELD ' + key)
        result[key] = value
    return result


def parse_json(text, label):
    try:
        return json.loads(text, object_pairs_hook=unique_object)
    except json.JSONDecodeError as error:
        raise GateError(f'INVALID_JSON {label}: {error.msg}') from error


def atomic_write(path, content, mode):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix='.feature-record-',
                                         suffix='.tmp', delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        temporary.chmod(mode)
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def snapshot(repo, staged=False, ref=None):
    files = {}
    if not staged and ref is None:
        names = git(repo, 'ls-files', '--cached', '--others', '--exclude-standard',
                    '--exclude=**/.feature-record-*.tmp', '-z')
        for name in sorted(set(p for p in names.split(b'\0') if p)):
            path = name.decode('utf-8')
            if not safe_path(path):
                raise GateError('UNSAFE_PATH ' + repr(path))
            full = repo / path
            probe = repo
            linked = False
            for part in PurePosixPath(path).parts:
                probe = probe / part
                if probe.is_symlink():
                    linked = True
                    break
            if linked:
                files[path] = ('120000', b'')  # Never read through a symlink or linked ancestor.
                continue
            try:
                mode = full.lstat().st_mode
            except FileNotFoundError:
                continue
            if not stat.S_ISREG(mode):
                files[path] = ('160000', b'')
                continue
            files[path] = ('100755' if mode & 0o111 else '100644', full.read_bytes())
        return files
    if staged:
        output = git(repo, 'ls-files', '--stage', '-z')
    else:
        commit = git(repo, 'rev-parse', '--verify', '--end-of-options', ref + '^{commit}').strip()
        output = git(repo, 'ls-tree', '-r', '-z', '--full-tree', commit.decode())
    records = []
    for entry in output.split(b'\0'):
        if not entry:
            continue
        fields, name = entry.split(b'\t', 1)
        path = name.decode('utf-8')
        if not safe_path(path):
            raise GateError('UNSAFE_PATH ' + repr(path))
        if staged:
            mode, oid, stage = fields.split()
            if stage != b'0':
                raise GateError('UNMERGED ' + path)
        else:
            mode, kind, oid = fields.split()
            if kind not in (b'blob', b'commit'):
                raise GateError('UNSUPPORTED_FILE ' + path)
        mode = mode.decode()
        if mode not in REGULAR:
            files[path] = (mode, b'')
        else:
            records.append((path, mode, oid))
    if records:
        blobs = io.BytesIO(git(repo, 'cat-file', '--batch',
                               input=b''.join(oid + b'\n' for _, _, oid in records)))
        for path, mode, oid in records:
            actual, kind, size = blobs.readline().split()
            if actual != oid or kind != b'blob':
                raise RuntimeError('Unexpected Git blob response for ' + path)
            content = blobs.read(int(size))
            if blobs.read(1) != b'\n':
                raise RuntimeError('Truncated Git blob response for ' + path)
            files[path] = (mode, content)
    return files


def policy(files):
    if CONFIG not in files:
        raise GateError('NOT_INITIALIZED: explicitly install Feature records first')
    mode, content = files[CONFIG]
    if mode not in REGULAR:
        raise GateError('UNSUPPORTED_FILE ' + CONFIG)
    data = parse_json(content.decode('utf-8'), CONFIG)
    if (not isinstance(data, dict) or set(data) != {'version', 'managed', 'tool', 'assets'}
            or type(data['version']) is not int or data['version'] != 1):
        raise GateError('INVALID_POLICY ' + CONFIG)
    scopes = data['managed']
    if (not isinstance(scopes, list) or not scopes or not all(safe_path(p) for p in scopes)
            or len(set(scopes)) != len(scopes) or not safe_path(data['tool'])):
        raise GateError('INVALID_POLICY source scope or tool')
    assets = data['assets']
    if (not isinstance(assets, dict) or not set(assets) <= ASSETS
            or not {CONTROL + '/run', CONTROL + '/hooks/pre-commit', CONTROL + '/hooks/pre-push'} <= set(assets)
            or not all(isinstance(v, str) and re.fullmatch(r'[a-f0-9]{64}', v) for v in assets.values())):
        raise GateError('INVALID_POLICY installation receipt')
    return data


def features(files):
    result = {}
    required = {'paths', 'fingerprint'}
    legacy = ({'status', 'paths', 'reviewed_code'}, {'title', 'status', 'code', 'tests', 'reviewed_code'})
    for path, (mode, content) in sorted(files.items()):
        if not path.startswith('docs/features/'):
            continue
        if mode not in REGULAR:
            raise GateError('UNSUPPORTED_FILE ' + path)
        if not path.endswith('.md'):
            continue
        slug = PurePosixPath(path).stem
        if path != f'docs/features/{slug}.md' or not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', slug):
            raise GateError('INVALID_FEATURE_PATH ' + path)
        text = content.decode('utf-8').replace('\r\n', '\n')
        if not text.startswith('---\n') or '\n---\n' not in text[4:]:
            raise GateError('INVALID_FRONT_MATTER ' + path)
        header, body = text[4:].split('\n---\n', 1)
        meta = parse_json(header, path)
        if not isinstance(meta, dict) or set(meta) not in (required, *legacy):
            raise GateError('INVALID_FIELDS ' + path)
        if 'paths' in meta:
            heading = body.lstrip().split('\n', 1)[0]
            title = heading[2:] if heading.startswith('# ') else ''
        else:
            title = meta['title']
        if (not isinstance(title, str) or not title.strip()
                or any(c in title for c in ('\n', '\r', '\t'))):
            raise GateError('INVALID_TITLE ' + path)
        if 'status' in meta and meta['status'] not in ('partial', 'implemented'):
            raise GateError('INVALID_STATUS ' + path)
        if not isinstance(meta.get('fingerprint', meta.get('reviewed_code')), str):
            raise GateError('INVALID_BASE ' + path)
        for field in (('paths',) if 'paths' in meta else ('code', 'tests')):
            patterns = meta[field]
            if (not isinstance(patterns, list) or not patterns or not all(safe_path(p) for p in patterns)
                    or len(set(patterns)) != len(patterns)):
                raise GateError('INVALID_REFERENCES ' + path + ': ' + field)
        for heading in ('当前行为', '限制与剩余'):
            sections = re.findall(r'^## ' + heading + r'\s*\n(.*?)(?=^## |\Z)',
                                  body, flags=re.MULTILINE | re.DOTALL)
            if len(sections) != 1 or not sections[0].strip():
                raise GateError('MISSING_SECTION ' + path + ': ' + heading)
        result[slug] = (path, title, meta, body)
    return result


def evidence(meta, files):
    selected = set()
    fields = ('paths',) if 'paths' in meta else ('code', 'tests')
    for pattern in (p for field in fields for p in meta[field]):
        matches = {p for p in files if fnmatch.fnmatchcase(p, pattern)}
        if not matches:
            raise GateError('MISSING_REFERENCE ' + pattern)
        if any(p.startswith('docs/features/') for p in matches):
            raise GateError('SELF_REFERENCE ' + pattern)
        for path in matches:
            if files[path][0] not in REGULAR:
                raise GateError('UNSUPPORTED_FILE ' + path)
        selected.update(matches)
    digest = hashlib.sha256()
    declaration = json.dumps({k: sorted(meta[k]) for k in fields},
                             sort_keys=True, separators=(',', ':')).encode('utf-8')
    digest.update(declaration + b'\0')
    for path in sorted(selected):
        mode, content = files[path]
        digest.update(path.encode('utf-8') + b'\0' + mode.encode() + b'\0')
        digest.update(hashlib.sha256(content).digest())
    return 'sha256:' + digest.hexdigest(), selected


def inspect(files):
    config = policy(files)
    rows, errors, owned = [], [], set()
    for path, expected in config['assets'].items():
        mode = '100644' if path == CONTROL + '/.gitattributes' else '100755'
        if (path not in files or files[path][0] != mode
                or hashlib.sha256(files[path][1]).hexdigest() != expected):
            errors.append('INSTALLATION_DRIFT ' + path + ': preserve custom changes; rerun installer for missing assets')
    if config['tool'] not in files or files[config['tool']][0] not in REGULAR:
        errors.append('MISSING_TOOL ' + config['tool'])
    for slug, (path, title, meta, _) in features(files).items():
        try:
            expected, selected = evidence(meta, files)
        except GateError as error:
            errors.append(f'{slug}: {error}')
            rows.append((slug, title, 'missing', path))
            continue
        owned.update(selected)
        state = 'fresh' if meta.get('fingerprint', meta.get('reviewed_code')) == expected else 'stale'
        rows.append((slug, title, state, path))
        if state == 'stale':
            errors.append('STALE ' + slug + ': review facts before refreshing its baseline')
    for path, (mode, _) in sorted(files.items()):
        if path.startswith('docs/features/') or not in_scope(path, config['managed']):
            continue
        if mode not in REGULAR:
            errors.append('UNSUPPORTED_FILE ' + path)
        elif path not in owned:
            errors.append('UNOWNED ' + path)
    return config, rows, errors


def install(repo, scopes):
    for path in (CONFIG, 'docs/features'):
        probe = repo
        parts = PurePosixPath(path).parts
        for index, part in enumerate(parts):
            probe = probe / part
            if probe.is_symlink() or (index < len(parts) - 1 and probe.exists() and not probe.is_dir()):
                raise GateError('ASSET_CONFLICT linked or invalid destination ' + path)
        if path == 'docs/features' and probe.exists() and not probe.is_dir():
            raise GateError('ASSET_CONFLICT ' + path)
    ignored = subprocess.run(['git', '-C', str(repo), 'check-ignore', '--no-index', '-q', '--', CONFIG],
                             capture_output=True, timeout=30)
    if ignored.returncode == 0:
        raise GateError('IGNORED_ASSET ' + CONFIG + ': track the gate so clones can restore it')
    if not (repo / CONFIG).exists():
        indexed = git(repo, 'ls-files', '--cached', '--', CONFIG).strip()
        committed = subprocess.run(['git', '-C', str(repo), 'cat-file', '-e', 'HEAD:' + CONFIG],
                                   capture_output=True, timeout=30).returncode == 0
        if indexed or committed:
            raise GateError('MISSING_CONFIG: restore project configuration; do not reset its managed scope')
    old = policy(snapshot(repo)) if (repo / CONFIG).exists() else None
    if scopes and (not all(safe_path(p) for p in scopes) or len(set(scopes)) != len(scopes)):
        raise GateError('INVALID_SCOPE source globs')
    scopes = list(dict.fromkeys((old['managed'] if old else []) + (scopes or [])))
    if not scopes:
        raise GateError('INVALID_SCOPE: first installation requires explicit --scope globs')
    source = Path(__file__).resolve()
    tool = CONTROL + '/tool.py'
    assets = {CONTROL + '/.gitattributes': b'* text eol=lf\n'}
    try:
        relative = source.relative_to(repo).as_posix()
    except ValueError:
        relative = None
    if relative and relative != tool:
        tracked = subprocess.run(['git', '-C', str(repo), 'ls-files', '--error-unmatch', '--', relative],
                                 capture_output=True, timeout=30)
        if tracked.returncode == 0:
            tool = relative
    if tool == CONTROL + '/tool.py':
        assets[tool] = source.read_bytes().replace(b'\r\n', b'\n')
    launcher = ('#!/bin/sh\nset -eu\nroot="$(cd "$(dirname "$0")/.." && pwd -P)"\n'
                'exec python3 "$root"/' + shlex.quote(tool) + ' --repo "$root" "$@"\n')
    assets[CONTROL + '/run'] = launcher.encode()
    assets[CONTROL + '/hooks/pre-commit'] = (
        '#!/bin/sh\nset -eu\nroot="$(git rev-parse --show-toplevel)"\n'
        'exec "$root/.feature-docs/run" check --staged\n').encode()
    assets[CONTROL + '/hooks/pre-push'] = (
        '#!/bin/sh\nset -eu\nroot="$(git rev-parse --show-toplevel)"\n'
        'while read -r local_ref local_oid remote_ref remote_oid; do\n'
        '  case "$local_oid" in ""|*[!0]*) ;; *) continue ;; esac\n'
        '  "$root/.feature-docs/run" check --ref "$local_oid"\ndone\n').encode()
    existing = subprocess.run(['git', '-C', str(repo), 'config', '--get', 'core.hooksPath'],
                              capture_output=True, text=True, timeout=30).stdout.strip()
    hook_path = CONTROL + '/hooks'
    if existing and existing != hook_path:
        raise GateError('HOOK_CONFLICT: preserve existing core.hooksPath=' + existing)
    if not existing:
        for name in ('pre-commit', 'pre-push'):
            hook = Path(git(repo, 'rev-parse', '--git-path', 'hooks/' + name).decode().strip())
            hook = hook if hook.is_absolute() else repo / hook
            if hook.exists() or hook.is_symlink():
                raise GateError('HOOK_CONFLICT: preserve existing ' + name)
    # Preflight every destination before writing any asset or changing Git config.
    for path, content in assets.items():
        target = repo / path
        probe = repo
        parts = PurePosixPath(path).parts
        for index, part in enumerate(parts):
            probe = probe / part
            if probe.is_symlink() or (index < len(parts) - 1 and probe.exists() and not probe.is_dir()):
                raise GateError('ASSET_CONFLICT linked or invalid destination ' + path)
        ignored = subprocess.run(['git', '-C', str(repo), 'check-ignore', '--no-index', '-q', '--', path],
                                 capture_output=True, timeout=30)
        if ignored.returncode == 0:
            raise GateError('IGNORED_ASSET ' + path + ': track the gate so clones can restore it')
        if target.exists():
            if not target.is_file():
                raise GateError('ASSET_CONFLICT ' + path)
            current = target.read_bytes()
            approved = old and old['assets'].get(path) == hashlib.sha256(current).hexdigest()
            if current != content and not approved:
                raise GateError('ASSET_CONFLICT modified asset ' + path)
    config = {'version': 1, 'managed': scopes, 'tool': tool,
              'assets': {p: hashlib.sha256(c).hexdigest() for p, c in sorted(assets.items())}}
    for path, content in assets.items():
        atomic_write(repo / path, content, 0o644 if path == CONTROL + '/.gitattributes' else 0o755)
    atomic_write(repo / CONFIG, (json.dumps(config, ensure_ascii=False, indent=2) + '\n').encode(), 0o644)
    (repo / 'docs/features').mkdir(parents=True, exist_ok=True)
    git(repo, 'config', '--local', 'core.hooksPath', hook_path)
    effective = git(repo, 'config', '--get', 'core.hooksPath').decode().strip()
    if effective != hook_path:
        raise GateError('HOOK_CONFLICT: worktree-specific config overrides the installed hooks')
    print('INSTALLED local record gates; scope=' + ', '.join(scopes))
    print('Project facts and review baselines were not generated or overwritten. Run check before delivery.')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='.', help='exact Git worktree, defaults to cwd')
    commands = parser.add_subparsers(dest='command', required=True)
    installer = commands.add_parser('install', help='explicitly install or upgrade only owned gate assets')
    installer.add_argument('--scope', action='append', help='explicitly add source globs; never remove existing scope')
    check = commands.add_parser('check', help='read-only record check; does not run business tests')
    target = check.add_mutually_exclusive_group()
    target.add_argument('--staged', action='store_true', help='validate the exact index')
    target.add_argument('--ref', help='validate an exact commit')
    commands.add_parser('list', help='compact index and explicit enforced scope')
    review = commands.add_parser('review', help='refresh one explicitly reviewed baseline, not test status')
    review.add_argument('feature')
    review.add_argument('--staged', action='store_true', help='bind current doc text to staged code')
    review.add_argument('--confirm', action='store_true', required=True)
    args = parser.parse_args(argv)
    try:
        root = Path(git(Path(args.repo).resolve(), 'rev-parse', '--show-toplevel').decode().strip()).resolve()
        if args.command == 'install':
            install(root, args.scope)
            return 0
        files = snapshot(root, staged=getattr(args, 'staged', False), ref=getattr(args, 'ref', None))
        if args.command == 'review':
            policy(files)
            if not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', args.feature):
                raise GateError('INVALID_FEATURE_ID ' + args.feature)
            current = snapshot(root) if args.staged else files
            path = f'docs/features/{args.feature}.md'
            if path not in current:
                raise GateError('UNKNOWN_FEATURE ' + args.feature)
            _, _, meta, body = features({path: current[path]})[args.feature]
            field = 'fingerprint' if 'fingerprint' in meta else 'reviewed_code'
            meta[field], _ = evidence(meta, files)
            destination = root / path
            payload = '---\n' + json.dumps(meta, ensure_ascii=False, indent=2) + '\n---\n' + body
            atomic_write(destination, payload.encode('utf-8'), stat.S_IMODE(destination.stat().st_mode))
            print('REVIEWED ' + args.feature + ' (baseline only; not staged; not a test result)')
            return 0
        config, rows, errors = inspect(files)
        if args.command == 'list':
            print('SCOPE\t' + ', '.join(config['managed']))
            for row in rows:
                print('\t'.join(row))
        for error in errors:
            print(error, file=sys.stderr)
        if errors:
            return 1
        if args.command == 'check':
            print(f'OK {len(rows)} features within declared scope; not a test result')
        return 0
    except (GateError, UnicodeDecodeError) as error:
        print(str(error), file=sys.stderr)
        return 1
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        print('ERROR ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
