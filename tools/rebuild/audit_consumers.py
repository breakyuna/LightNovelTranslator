#!/usr/bin/env python3
"""Static consumer audit for the recorded LG behaviour index.

This resolves textual evidence only: a route registration, the service methods it
calls, the files that reference it, and test files that mention it. It cannot prove
runtime wiring, parameter contracts or behavioural parity, and it never executes LG,
installs packages or calls a model. Undersolved entries stay explicitly listed.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import re

ROUTE_PREFIXES = (
    'context.postJson(', 'context.request(', 'context.app.get(', 'context.app.post(',
)
NOISY_RECEIVERS = {
    'Response', 'JSON', 'Object', 'String', 'Number', 'Boolean', 'Array',
    'Date', 'Promise', 'console', 'path', 'Readable', 'URL', 'Math', 'Set', 'Map',
}
KEYWORDS = {
    'if', 'for', 'while', 'switch', 'catch', 'return', 'typeof', 'new', 'await',
    'function', 'super', 'constructor', 'void', 'throw', 'else', 'do',
}
CALL = re.compile(r'(?<![\w$.])([A-Za-z_$][\w$]*)((?:\.[A-Za-z_$][\w$]*)*)\s*\(')
CHAINED_CALL = re.compile(r'\)\s*\.\s*([A-Za-z_$][\w$]*)\s*\(')
# Reviewed adapter directories for the 15 discovery formats; ids without a directory
# stay unresolved instead of being silently grouped under a neighbour.
FORMAT_DIRECTORIES = {
    'txt': 'src/backend/file/txt',
    'md': 'src/backend/file/markdown',
    'epub': 'src/backend/file/epub',
    'srt': 'src/backend/file/subtitle',
    'vtt': 'src/backend/file/subtitle',
    'lrc': 'src/backend/file/subtitle',
    'ass': 'src/backend/file/subtitle',
    'ssa': 'src/backend/file/subtitle',
    'json': 'src/backend/file/kvjson',
    'xlsx': 'src/backend/file/xlsx',
    'trans': 'src/backend/file/trans',
    'rpy': 'src/backend/file/renpy',
    'rpgmaker_mv_mz': 'src/backend/file/rpgmaker-mv-mz',
    'rpgmaker_rgss': 'src/backend/file/rpgmaker-rgss',
    'pdf': 'src/backend/file/pdf',
}
BACKEND_ONLY_ROUTES = {'/api/health', '/api/events/stream'}


def read_lines(path):
    return path.read_text(errors='replace').splitlines()


def index_tree(root):
    files = {}
    for path in sorted((root / 'src').rglob('*')):
        if path.is_file() and path.suffix in {'.ts', '.tsx', '.mjs'} and '.test.' not in path.name:
            files[path.relative_to(root).as_posix()] = read_lines(path)
    return files


def containing_call(text, position):
    """Return the whole call expression around a route key, balancing strings."""
    start = max(text.rfind(prefix, 0, position) for prefix in ROUTE_PREFIXES)
    if start < 0:
        return ''
    depth = 0
    index = text.find('(', start)
    quote = None
    while index < len(text):
        character = text[index]
        if quote is not None:
            if character == '\\':
                index += 1
            elif character == quote:
                quote = None
        elif character in '\'"`':
            quote = character
        elif text.startswith('//', index):
            index = text.find('\n', index)
            if index < 0:
                return text[start:]
        elif text.startswith('/*', index):
            index = text.find('*/', index + 2)
            if index < 0:
                return text[start:]
            index += 1
        elif character == '(':
            depth += 1
        elif character == ')':
            depth -= 1
            if depth == 0:
                return text[start:index + 1]
        index += 1
    return text[start:]


GENERIC_TOKENS = {'services', 'context', 'body', 'request', 'response', 'options', 'this'}


def name_tokens(name):
    """Split camelCase/snake_case receiver names into path hints for definition ranking."""
    spaced = re.sub(r'([a-z0-9])([A-Z])', r'\1-\2', name)
    tokens = [token for token in re.split(r'[^A-Za-z0-9]+', spaced.lower()) if len(token) >= 3]
    return [token for token in tokens if token not in GENERIC_TOKENS]


def callees(call_text):
    """Collect call targets with receiver-path hints, in source order."""
    found = []

    def add(target, method, hints):
        if not method or method in KEYWORDS:
            return
        if any(existing['target'] == target for existing in found):
            return
        found.append({'target': target, 'method': method, 'hints': hints})

    for match in CALL.finditer(call_text):
        base, chain = match.group(1), match.group(2)
        parts = [part for part in chain.split('.') if part]
        if base in NOISY_RECEIVERS:
            continue
        if base == 'context' and parts and parts[0] in {'app', 'postJson', 'request'}:
            continue
        if base == 'hono_context':
            continue
        target = '.'.join([base, *parts]) if parts else base
        method = parts[-1] if parts else base
        if base == 'context' and not parts:
            continue
        hints = []
        if base not in {'context'}:
            hints.extend(name_tokens(base))
        for part in parts[:-1]:
            hints.extend(name_tokens(part))
        add(target, method, sorted(set(hints)))
    for match in CHAINED_CALL.finditer(call_text):
        add(match.group(1), match.group(1), name_tokens(match.group(1)))
    return found


def recorded_line_ok(lines, entry):
    key = entry['key']
    number = entry.get('line')
    if not number or number > len(lines):
        return False
    window = lines[max(0, number - 2):number + 1]
    return any(key in line for line in window)


def path_matches_hints(path, hints):
    lower = path.lower()
    return any(hint in lower or hint.rstrip('s') in lower for hint in hints)


def find_definitions(files, method, hints, limit=4):
    """Resolve declarations and implementations by name; both are evidence, neither is proof."""
    pattern = re.compile(
        r'^\s*(?:(?:public|private|protected|readonly|static|async|get|set|export|function|declare)\s+)*'
        + re.escape(method) + r'\s*[:=(]'
    )
    found = []
    for path in sorted(files):
        if not path.startswith('src/backend/'):
            continue
        per_file = 0
        for number, line in enumerate(files[path], 1):
            if method in line and pattern.search(line):
                found.append({'path': path, 'line': number, 'signature': line.strip()[:160]})
                per_file += 1
                if per_file >= 2:
                    break
    found.sort(key=lambda item: (0 if path_matches_hints(item['path'], hints) else 1, item['path']))
    return found[:limit]


def route_entry(entry, files, api_source):
    key = entry['key']
    position = api_source.find('"' + key + '"')
    if position < 0:
        return {'status': 'unresolved', 'reason': 'route key not found in api-routes.ts'}
    call_text = containing_call(api_source, position)
    if not call_text:
        return {'status': 'unresolved', 'reason': 'route call could not be extracted'}
    calls = callees(call_text)
    methods = [call['method'] for call in calls]
    definitions = []
    for call in calls:
        for found in find_definitions(files, call['method'], call['hints']):
            if call['hints'] and path_matches_hints(found['path'], call['hints']):
                match = 'owner_hint'
            elif found['path'].endswith('api/api-gateway-server.ts'):
                match = 'wiring'  # composition root binds the context capability
            elif found['path'] == entry['source']:
                match = 'same_file_helper'
            elif len(name_tokens(call['method'])) >= 2:
                # Compound names (list_roots, readManifest) are specific enough to be
                # evidence; single generic words (read, query, delete) are not.
                match = 'specific_name'
            else:
                match = 'name_only'
            definitions.append({'call': call['target'], 'match': match, **found})
    hinted = any(definition['match'] in {'owner_hint', 'wiring', 'specific_name'} for definition in definitions)
    frontend = {reference['path'] for reference in entry.get('references', [])
                if reference['path'].startswith('src/frontend/')}
    prefix_match = False
    if not frontend and ':' in key:
        # Clients build parameterised URLs with template strings, so the recorded key
        # never appears literally; the static prefix is the honest evidence available.
        prefix = key.split('/:', 1)[0] + '/'
        frontend = {path for path in files
                    if path.startswith('src/frontend/') and prefix in '\n'.join(files[path])}
        prefix_match = bool(frontend)
    frontend = sorted(frontend)
    tests = sorted({reference['path'] for reference in entry.get('references', [])
                    if '.test.' in reference['path']})
    source_ok = recorded_line_ok(api_source.splitlines(), entry)
    if not definitions:
        status = 'partial'
        reason = 'no backend method definition resolved'
    elif not hinted:
        status = 'partial'
        reason = 'definitions are name-only matches without receiver-path evidence'
    elif frontend or key in BACKEND_ONLY_ROUTES:
        status = 'resolved'
        reason = 'handler, receiver-matched definition and caller evidence found'
    else:
        status = 'partial'
        reason = 'handler resolved but no frontend caller reference'
    return {
        'status': 'drift' if not source_ok else status,
        'reason': reason if source_ok else 'recorded source line no longer contains the key',
        'source_ok': source_ok,
        'handler_methods': methods,
        'definitions': definitions,
        'frontend_callers': frontend,
        'frontend_match': 'static_prefix' if prefix_match else ('literal_key' if frontend else 'none'),
        'tests': tests,
    }


def reference_entry(entry, files, source_lines=None):
    references = entry.get('references', [])
    backend = sorted({reference['path'] for reference in references if reference['path'].startswith('src/backend/')})
    frontend = sorted({reference['path'] for reference in references if reference['path'].startswith('src/frontend/')})
    tests = sorted({reference['path'] for reference in references if '.test.' in reference['path']})
    if backend and frontend:
        status = 'referenced_in_both'
    elif backend:
        status = 'backend_only'
    elif frontend:
        status = 'frontend_only'
    else:
        status = 'no_textual_reference'
    return {
        'status': status,
        'source_ok': recorded_line_ok(source_lines, entry) if source_lines else True,
        'backend_files': backend,
        'frontend_files': frontend,
        'tests': tests,
    }


def page_entry(entry, root, files):
    registry = (root / 'src/frontend/app/navigation/screen-registry.ts').read_text()
    source_ok = recorded_line_ok(registry.splitlines(), entry)
    pattern = re.compile(
        r'["\']?' + re.escape(entry['key']) + r'["\']?\s*:\s*\{[^}]*?component:\s*([A-Za-z_$][\w$]*)',
        re.S,
    )
    match = pattern.search(registry)
    component = match.group(1) if match else None
    imports = []
    if component:
        for path, lines in files.items():
            if path.startswith('src/frontend/') and re.search(
                r'import\s*\{[^}]*\b' + re.escape(component) + r'\b[^}]*\}\s*from\s*["\']([^"\']+)',
                '\n'.join(lines),
            ):
                imports.append(path)
    # The two replacement screens share the generic text-replacement page, as the
    # registry maps both variants onto one component.
    page_name = {
        'project-home': 'project-page',
        'pre-translation-replacement': 'text-replacement-page',
        'post-translation-replacement': 'text-replacement-page',
    }.get(entry['key'], entry['key'] + '-page')
    page_directory = 'src/frontend/pages/' + page_name
    exists = (root / page_directory).is_dir()
    return {
        'status': 'drift' if not source_ok else ('resolved' if component and exists else 'partial'),
        'source_ok': source_ok,
        'component': component,
        'component_importers': sorted(set(imports)),
        'page_directory': page_directory,
        'page_directory_exists': exists,
    }


def format_entry(entry, root):
    source_ok = recorded_line_ok((root / entry['source']).read_text().splitlines(), entry)
    directory = FORMAT_DIRECTORIES.get(entry['key'])
    if directory is None:
        return {'status': 'unresolved', 'source_ok': source_ok,
                'reason': 'no reviewed adapter directory mapping'}
    folder = root / directory
    if not folder.is_dir():
        return {'status': 'unresolved', 'source_ok': source_ok,
                'reason': 'adapter directory missing', 'directory': directory}
    adapters = sorted(
        path.relative_to(root).as_posix()
        for path in folder.rglob('*')
        if path.is_file() and path.suffix in {'.ts', '.tsx'} and '.test.' not in path.name
    )
    tests = sorted(
        path.relative_to(root).as_posix()
        for path in folder.rglob('*.test.*')
    )
    return {
        'status': 'drift' if not source_ok else 'adapter_dir_found',
        'source_ok': source_ok,
        'directory': directory,
        'adapters': adapters[:20],
        'tests': tests[:20],
    }


def workspace_api_entry(entry, files):
    key = entry['key']
    source_lines = files.get(entry['source'])
    source_ok = recorded_line_ok(source_lines, entry) if source_lines else False
    usages = []
    for path in sorted(files):
        if not (path.startswith('builtin/') or path.startswith('src/backend/agent/workspace/')):
            continue
        for number, line in enumerate(files[path], 1):
            if key in line and path != entry['source']:
                usages.append({'path': path, 'line': number})
    status = 'used' if usages else 'declared_only'
    return {'status': 'drift' if not source_ok else status, 'source_ok': source_ok, 'usages': usages[:20]}


def package_entry(entry, files, root):
    name = entry['key']
    packages = json.loads((root / 'package.json').read_text()).get('workspacePackages', [])
    source_ok = name in packages
    usages = []
    for path in sorted(files):
        if not (path.startswith('builtin/') or path.startswith('src/backend/') or
                path.startswith('src/frontend/') or path.startswith('src/shared/')):
            continue
        for number, line in enumerate(files[path], 1):
            if name in line and ('import' in line or 'require(' in line):
                usages.append({'path': path, 'line': number})
    status = 'imported' if usages else 'not_imported_in_audited_sources'
    return {'status': 'drift' if not source_ok else status, 'source_ok': source_ok, 'usages': usages[:20]}


def skill_entry(entry, root):
    folder = root / entry['key']
    source_ok = (root / entry['source']).is_file()
    if not folder.is_dir() or not source_ok:
        return {'status': 'unresolved', 'source_ok': source_ok, 'reason': 'skill resources missing'}
    resources = sorted(
        path.relative_to(root).as_posix() for path in folder.rglob('*')
        if path.is_file() and '.test.' not in path.name
    )
    return {
        'status': 'resource_present',
        'source_ok': source_ok,
        'resources': resources[:40],
        'resource_count': len(resources),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lg', type=Path, required=True)
    parser.add_argument('--index', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.lg.resolve()
    document = json.loads(args.index.read_text())
    entries = document['entries']
    files = index_tree(root)
    # builtin scripts are not part of the backend TypeScript index
    for path in sorted((root / 'builtin').rglob('*')):
        if path.is_file() and path.suffix in {'.js', '.mjs', '.md'}:
            files[path.relative_to(root).as_posix()] = read_lines(path)
    api_source = (root / 'src/backend/api/api-routes.ts').read_text()

    audited = []
    for entry in entries:
        kind = entry['kind']
        if kind == 'route':
            evidence = route_entry(entry, files, api_source)
        elif kind == 'setting':
            evidence = reference_entry(entry, files, (root / entry['source']).read_text().splitlines())
        elif kind == 'page':
            evidence = page_entry(entry, root, files)
        elif kind == 'format':
            evidence = format_entry(entry, root)
        elif kind == 'workspace-api':
            evidence = workspace_api_entry(entry, files)
        elif kind == 'workspace-package':
            evidence = package_entry(entry, files, root)
        elif kind == 'skill':
            evidence = skill_entry(entry, root)
        else:
            evidence = {'status': 'unresolved', 'reason': 'unknown entry kind'}
        audited.append({
            'kind': kind,
            'key': entry['key'],
            'candidate_features': entry['candidate_features'],
            'source': entry['source'],
            'review': evidence,
        })

    counts = Counter((entry['kind'], entry['review']['status']) for entry in audited)
    needs_review = [entry for entry in audited if entry['review']['status'] in
                    {'unresolved', 'partial', 'drift', 'declared_only',
                     'not_imported_in_audited_sources', 'no_textual_reference',
                     'frontend_only', 'backend_only'}]
    result = {
        'schema_version': 1,
        'purpose': 'Static consumer evidence only; not runtime wiring or behaviour parity. '
                   'Undersolved entries remain listed and must be reviewed by hand.',
        'entry_count': len(audited),
        'status_counts': {f'{kind}:{status}': count for (kind, status), count in sorted(counts.items())},
        'needs_review': [{'kind': entry['kind'], 'key': entry['key'], 'status': entry['review']['status']}
                         for entry in needs_review],
        'entries': audited,
    }
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result['status_counts'], ensure_ascii=False, sort_keys=True))
    print(f'needs review: {len(needs_review)}/{len(audited)}')


if __name__ == '__main__':
    main()
