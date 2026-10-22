#!/usr/bin/env python3
"""Static source discovery, not proof of runtime parity or a substitute for review."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re

# Reviewed route families. Unknown families fail rather than silently mapping to OP01.
ROUTE_FAMILIES = [
    ('/api/agent/write-approval/', ['AG12']),
    ('/api/agent/question/', ['AG11']),
    ('/api/agent/queue/', ['AG04']),
    ('/api/agent/round/', ['AG07']),
    ('/api/agent/context/', ['AG09']),
    ('/api/agent/personality/', ['WS09']),
    ('/api/agent/workspace/', ['WS01', 'WS05', 'AG13']),
    ('/api/agent/save-path/', ['WS05', 'UX07']),
    ('/api/agent/uploads', ['AG13']),
    ('/api/skills/', ['WS07', 'WS08']),
    ('/api/session/source-files/', ['PR01']),
    ('/api/session/project/', ['PR01', 'PR02', 'PR07', 'PR09']),
    ('/api/workbench/settings-alignment/', ['PR07']),
    ('/api/workbench/translation/', ['PR08', 'TR01']),
    ('/api/workbench/file', ['PR04', 'PR08']),
    ('/api/proofreading/', ['QL07', 'QL09']),
    ('/api/quality/statistics/', ['QL10']),
    ('/api/quality/prompts/presets', ['QL05', 'QL06']),
    ('/api/quality/prompts/', ['QL06']),
    ('/api/quality/rules/presets', ['QL05']),
    ('/api/quality/rules/import', ['QL04']),
    ('/api/quality/rules/export', ['QL04']),
    ('/api/quality/rules/', ['QL01', 'QL02', 'QL03']),
    ('/api/translation/files/', ['UX03', 'UX07']),
    ('/api/settings/recent-projects/', ['PR02']),
    ('/api/settings/', ['UX06', 'PR07']),
    ('/api/models/auth/', ['MO04']),
    ('/api/models/catalog/', ['MO05']),
    ('/api/models/', ['MO01', 'MO06', 'MO09']),
    ('/api/batch-translation/', ['TR01', 'TR10', 'TR12']),
    ('/api/logs/', ['OP04']),
    ('/api/diagnostics/', ['OP04', 'OP05']),
    ('/api/web/', ['UX07', 'OP03']),
]
ROUTE_EXACT = {
    '/api/health': ['OP09'], '/api/events/stream': ['OP05'],
    '/api/runtime/snapshot': ['OP01'], '/api/workbench/snapshot': ['UX03'],
    '/api/project/translation-stats': ['TR11'], '/api/agent/files': ['WS05'],
    '/api/agent/snapshot': ['AG01', 'AG14'], '/api/agent/message': ['AG01', 'AG03'],
    '/api/agent/input-status': ['AG03'], '/api/agent/continue': ['AG06', 'AG10'],
    '/api/agent/stop': ['AG06'], '/api/agent/reset': ['AG07', 'WS01'],
}
SETTING_FEATURES = {
    'agent_approval_mode': ['AG12'], 'app_language': ['UX08'],
    'source_language': ['PR07', 'TR02'], 'target_language': ['PR07', 'TR07'],
    'project_save_mode': ['PR01', 'UX07'], 'project_fixed_path': ['PR01', 'UX07'],
    'output_folder_open_on_finish': ['UX07'], 'request_timeout': ['MO11'],
    'preceding_lines_threshold': ['TR04'], 'clean_ruby': ['TR05'],
    'deduplication_in_bilingual': ['FM01'], 'write_translated_name_fields_to_file': ['TR06'],
    'prompt_enhancement_enable': ['QL06'], 'mtool_optimizer_enable': ['TR05'],
    'skip_duplicate_source_text_enable': ['TR03'], 'glossary_default_preset': ['QL05'],
    'text_preserve_default_preset': ['QL05'], 'pre_translation_replacement_default_preset': ['QL05'],
    'post_translation_replacement_default_preset': ['QL05'],
    'translation_custom_prompt_default_preset': ['QL05', 'QL06'], 'recent_projects': ['PR02'],
}
PAGE_FEATURES = {
    'project-home': ['UX01'], 'model': ['MO01', 'UX06'], 'agent': ['UX02'],
    'proofreading': ['UX04'], 'workbench': ['UX03'], 'basic-settings': ['UX06'],
    'expert-settings': ['UX06'], 'glossary': ['QL01', 'UX05'], 'skills': ['WS07', 'UX05'],
    'text-preserve': ['QL02', 'UX05'], 'pre-translation-replacement': ['QL03', 'UX05'],
    'post-translation-replacement': ['QL03', 'UX05'], 'custom-prompt': ['QL06', 'UX05'],
    'laboratory': ['UX06'],
}
FORMATS = {'txt': 'FM01', 'md': 'FM02', 'epub': 'FM03', 'srt': 'FM04', 'vtt': 'FM04',
           'lrc': 'FM04', 'ass': 'FM05', 'ssa': 'FM05', 'json': 'FM06', 'xlsx': 'FM07',
           'trans': 'FM08', 'rpy': 'FM09', 'rpgmaker_mv_mz': 'FM10', 'rpgmaker_rgss': 'FM11', 'pdf': 'FM12'}


def route_features(key):
    if key in ROUTE_EXACT:
        return ROUTE_EXACT[key]
    for prefix, ids in ROUTE_FAMILIES:
        if key.startswith(prefix):
            return ids
    raise ValueError('Unmapped route family: ' + key)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lg', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.lg.resolve()
    entries = []

    def add(kind, path, pattern, mapping):
        text = (root / path).read_text()
        for match in re.finditer(pattern, text, re.M):
            key = match.group(1)
            entries.append({'kind': kind, 'key': key, 'source': path,
                            'line': text.count('\n', 0, match.start()) + 1,
                            'candidate_features': mapping(key), 'consumer_review': 'pending'})

    add('route', 'src/backend/api/api-routes.ts', r'context\.(?:postJson|request|app\.(?:get|post))\(\s*(?:"(?:GET|POST)",\s*)?"(/api/[^\"]+)"', route_features)
    add('setting', 'src/domain/setting.ts', r'^    ([a-z_]+): Type\.', lambda key: SETTING_FEATURES[key])
    add('page', 'src/frontend/app/navigation/screen-registry.ts', r'^  ["\']?([a-z][a-z-]+)["\']?: \{', lambda key: PAGE_FEATURES[key])
    add('format', 'src/shared/project-source-formats.ts', r'^    id: "([^\"]+)"', lambda key: [FORMATS[key]])
    add('workspace-api', 'src/backend/agent/workspace/runtime/api.ts', r'^  (contract|userSkillDirectory|emitImage|doing|host): (?!unknown)', lambda key: ['WS02', 'WS06', 'WS09'])
    package = json.loads((root / 'package.json').read_text())
    for name in package['workspacePackages']:
        entries.append({'kind': 'workspace-package', 'key': name, 'source': 'package.json',
                        'candidate_features': ['WS03'], 'consumer_review': 'pending'})
    for path in sorted((root / 'builtin').rglob('SKILL.md')):
        entries.append({'kind': 'skill', 'key': path.parent.relative_to(root).as_posix(),
                        'source': path.relative_to(root).as_posix(),
                        'candidate_features': ['WS03', 'WS07'], 'consumer_review': 'pending'})

    # Textual references are leads, not assertions that a particular consumer executes.
    files = []
    for base in ('src', 'builtin'):
        for path in sorted((root / base).rglob('*')):
            if path.is_file() and path.suffix in {'.ts', '.tsx', '.js', '.mjs', '.md'}:
                files.append((path.relative_to(root).as_posix(), path.read_text().splitlines()))
    for entry in entries:
        references = []
        if entry['kind'] in {'route', 'setting', 'workspace-package', 'workspace-api'}:
            key = entry['key']
            pattern = re.compile(re.escape(key) if entry['kind'] in {'route', 'workspace-package'}
                                 else r'\b' + re.escape(key) + r'\b')
            for path, lines in files:
                if path == entry['source']:
                    continue
                matching = [number for number, line in enumerate(lines, 1) if pattern.search(line)]
                if matching:
                    references.append({'path': path, 'lines': matching,
                                       'role': 'test' if '.test.' in path else 'source-reference'})
        entry['references'] = references
    result = {'schema_version': 2,
              'purpose': 'Discovery only; textual references are not verified consumers or parity evidence',
              'entries': entries}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(dict(sorted(Counter(entry['kind'] for entry in entries).items()))))


if __name__ == '__main__':
    main()
