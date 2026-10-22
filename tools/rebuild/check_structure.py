#!/usr/bin/env python3
"""Static module/import checks only. Does not compile, resolve or execute Kotlin."""
import json
from pathlib import Path
import re
import tomllib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MODULES = {'domain', 'engine', 'agent', 'storage', 'formats', 'workspace'}
JVM_MODULES = {'domain', 'engine'}
DOCS = ROOT / 'docs/plans/2026-10-22-native-lg-rebuild'
errors = []

# Known Kotlin 2.3 / Android API landmines from AGENTS.md. Detection is textual;
# it cannot prove that an expression is safe in every context.
LANDMINE_PATTERNS = {
    r'\bcreateTempDir\s*\(': 'createTempDir is an ERROR-level deprecation',
    r'\.groups\s*\[\s*["A-Za-z_]': 'named regex group access is API 26+; resolve group indexes instead',
    r'\(\?[a-zA-Z]*U\)': 'inline (?U) regex flag is ICU-incompatible on API 34+',
    r'\bkotlinx\.coroutines\.CoroutineContext\b': 'use kotlin.coroutines.CoroutineContext',
    r'\bjava\.net\.(?:URL|HttpURLConnection)\b': 'agent/domain code must use the model/network port',
}


def require(condition, message):
    if not condition:
        errors.append(message)


def kotlin_delimiters_balanced(text):
    """Lexical balance only: strings, chars and comments are skipped, not compiled."""
    opening = '({['
    closing = ')}]'
    stack = []
    index = 0
    state = 'code'
    comment_depth = 0
    while index < len(text):
        character = text[index]
        if state == 'code':
            if text.startswith('//', index):
                state = 'line'
                index += 2
                continue
            if text.startswith('/*', index):
                state = 'block'
                comment_depth = 1
                index += 2
                continue
            if text.startswith('"""', index):
                state = 'raw'
                index += 3
                continue
            if character == '"':
                state = 'string'
                index += 1
                continue
            if character == "'":
                state = 'char'
                index += 1
                continue
            if character in opening:
                stack.append(character)
            elif character in closing:
                if not stack or stack[-1] != opening[closing.index(character)]:
                    return False
                stack.pop()
            index += 1
        elif state == 'line':
            if character == '\n':
                state = 'code'
            index += 1
        elif state == 'block':
            if text.startswith('/*', index):
                comment_depth += 1
                index += 2
            elif text.startswith('*/', index):
                comment_depth -= 1
                index += 2
                if comment_depth == 0:
                    state = 'code'
            else:
                index += 1
        elif state == 'string':
            if character == '\\':
                index += 2
                continue
            if character == '"':
                state = 'code'
            index += 1
        elif state == 'raw':
            if text.startswith('"""', index):
                state = 'code'
                index += 3
                continue
            index += 1
        else:  # char literal
            if character == '\\':
                index += 2
                continue
            if character == "'":
                state = 'code'
            index += 1
    return not stack and state in {'code', 'line'}


settings = (ROOT / 'settings.gradle.kts').read_text()
registered = set(re.findall(r'":core:([a-z]+)"', settings))
require(registered == MODULES, 'settings must register exactly the six native core modules')
catalog = tomllib.loads((ROOT / 'gradle/libs.versions.toml').read_text())
for alias in ('android-library', 'kotlin-jvm'):
    require(alias in catalog['plugins'], 'missing catalog plugin: ' + alias)
require(catalog['plugins']['android-library']['version']['ref'] == 'agp', 'AGP version drift')
require(catalog['plugins']['kotlin-jvm']['version']['ref'] == 'kotlin', 'Kotlin version drift')
for name in sorted(MODULES):
    directory = ROOT / 'core' / name
    script = directory / 'build.gradle.kts'
    require(script.is_file(), f'{name}: missing build script')
    if not script.is_file():
        continue
    text = script.read_text()
    edges = re.findall(r'(api|implementation|testImplementation)\(project\("([^"]+)"\)\)', text)
    production = [(configuration, target) for configuration, target in edges if configuration != 'testImplementation']
    expected = set() if name == 'domain' else {':core:domain'}
    require({target for _, target in production} == expected,
            f'{name}: unexpected production project dependency {production}')
    # Module APIs accept/return domain types (RunIdentity, AgentMessage, RunLease...).
    # implementation would hide those types from every consumer's compile classpath.
    if name != 'domain':
        require(('api', ':core:domain') in production,
                f'{name}: domain types are part of the public API, so :core:domain must use api()')
    test_dependencies = {target for configuration, target in edges if configuration == 'testImplementation'}
    require(test_dependencies <= ({':core:engine'} if name == 'agent' else set()),
            f'{name}: unexpected test-only project dependency {test_dependencies}')
    require(not re.search(r'\brepositories\s*\{', text), f'{name}: repositories must stay in settings')
    require('JVM_17' in text and 'VERSION_17' in text, f'{name}: missing Java/Kotlin 17 target')
    plugin = 'kotlin.jvm' if name in JVM_MODULES else 'android.library'
    require(f'alias(libs.plugins.{plugin})' in text, f'{name}: wrong plugin')
    if name not in JVM_MODULES:
        try:
            ET.parse(directory / 'src/main/AndroidManifest.xml')
        except (OSError, ET.ParseError) as error:
            errors.append(f'{name}: invalid manifest: {type(error).__name__}')
        require('libs.versions.androidMinSdk' in text and 'libs.versions.androidCompileSdk' in text,
                f'{name}: SDK configuration must use catalog')
    main_sources = sorted(directory.glob('src/main/**/*.kt'))
    if main_sources:
        require(any(directory.glob('src/test/**/*.kt')), f'{name}: main sources without any test source')
    for source in main_sources:
        body = source.read_text()
        marker = 'nativecore/'
        relative = source.as_posix().split(marker, 1)[1]
        expected_package = 'com.breakyuna.noveltranslator.nativecore.' + relative.rsplit('/', 1)[0].replace('/', '.')
        require(f'package {expected_package}' in body,
                f'{source.relative_to(ROOT)}: package must match its directory ({expected_package})')
        for imported in re.findall(r'^import\s+([^\s]+)', body, re.M):
            local = 'com.breakyuna.noveltranslator.'
            if imported.startswith(local):
                require(imported.startswith(local + 'nativecore.domain.') or
                        imported.startswith(local + 'nativecore.' + name + '.'),
                        f'{source.relative_to(ROOT)}: forbidden cross-module/legacy import {imported}')
            if name == 'agent':
                require(not imported.startswith(('okhttp3.', 'io.ktor.client.', 'java.net.', 'javax.net.')),
                        f'agent must call the shared model/network port: {imported}')
            if name == 'domain':
                require(not imported.startswith(('android.', 'androidx.', 'ai.koog.', 'kotlinx.coroutines.')),
                        f'domain must stay independent: {imported}')
        for pattern, reason in LANDMINE_PATTERNS.items():
            require(not re.search(pattern, body), f'{source.relative_to(ROOT)}: {reason}')
        require(not re.search(r'\bTODO\s*\(', body), f'{source}: executable TODO')
        require(kotlin_delimiters_balanced(body),
                f'{source.relative_to(ROOT)}: unbalanced Kotlin delimiters')
    for source in sorted(directory.glob('src/test/**/*.kt')):
        body = source.read_text()
        require(kotlin_delimiters_balanced(body),
                f'{source.relative_to(ROOT)}: unbalanced Kotlin delimiters')
        relative = source.as_posix().split('nativecore/', 1)[1]
        test_package = 'com.breakyuna.noveltranslator.nativecore.' + relative.rsplit('/', 1)[0].replace('/', '.')
        require(f'package {test_package}' in body,
                f'{source.relative_to(ROOT)}: package must match its directory ({test_package})')
        for pattern, reason in LANDMINE_PATTERNS.items():
            require(not re.search(pattern, body), f'{source.relative_to(ROOT)}: {reason}')
        require(not re.search(r'\bTODO\s*\(', body), f'{source}: executable TODO')
app = (ROOT / 'app/build.gradle.kts').read_text()
require({':core:' + name for name in MODULES} <= set(re.findall(r'project\("([^"]+)"\)', app)),
        'app must assemble every native module')
for name in JVM_MODULES:
    require(f':core:{name}:test' in (ROOT / '.github/workflows/android.yml').read_text(),
            name + ': JVM tests missing from CI command')
for document in sorted(DOCS.glob('*.md')):
    for target in re.findall(r'\[[^\]]*\]\(([^)\s]+)\)', document.read_text()):
        if target.startswith(('http://', 'https://', '#')):
            continue
        relative = target.split('#', 1)[0]
        require((document.parent / relative).resolve().exists(),
                f'{document.name}: broken relative link {target}')
# The coverage report is generated from the matrix; keep it in sync so a new or
# renamed feature ID cannot silently leave a stale "covered" list.
matrix_ids = set(re.findall(r'^\|\s*([A-Z]{2}\d{2})\s*\|', (DOCS / 'FEATURE_MATRIX.md').read_text(), re.M))
coverage = json.loads((DOCS / 'FEATURE_COVERAGE.json').read_text())
require(set(coverage.get('entries', {})) == matrix_ids,
        'FEATURE_COVERAGE.json is stale; rerun tools/rebuild/coverage_report.py')
require(set(coverage.get('uncovered', [])) <= matrix_ids,
        'FEATURE_COVERAGE.json lists unknown uncovered IDs')
if errors:
    raise SystemExit('\n'.join(errors))
print('Static structure: six native modules, api() domain edges, dependency/import boundaries, '
      'packages, manifests, landmines, Kotlin delimiter balance, docs links, catalog and CI task '
      'references checked; no build executed.')
