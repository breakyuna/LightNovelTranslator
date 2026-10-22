#!/usr/bin/env python3
"""Combine discovery entries, reviewed source bindings and native-only notes per feature ID.

A feature is "covered" when at least one of these holds:
  * the LG behaviour index discovered an entry for it;
  * a reviewed (path, marker) binding in SOURCE_BINDINGS.json exists;
  * it is recorded as native-only with a reason (no LG source to cite).

Coverage here means "there is traceable evidence to review", never that the feature is
implemented or verified. Uncovered IDs are the R00 manual binding list.
"""
import argparse
from collections import Counter
import json
from pathlib import Path
import re

MATRIX_ROW = re.compile(r'^\|\s*([A-Z]{2}\d{2})\s*\|', re.M)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--matrix', type=Path, required=True)
    parser.add_argument('--audit', type=Path, required=True)
    parser.add_argument('--bindings', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()

    ids = sorted(dict.fromkeys(MATRIX_ROW.findall(args.matrix.read_text())))
    audit_entries = json.loads(args.audit.read_text())['entries']
    binding_entries = json.loads(args.bindings.read_text())['entries']

    discovery = {feature: [] for feature in ids}
    unknown = Counter()
    for entry in audit_entries:
        for feature in entry.get('candidate_features', []):
            if feature in discovery:
                discovery[feature].append({
                    'kind': entry['kind'],
                    'key': entry['key'],
                    'status': entry['review']['status'],
                })
            else:
                unknown[feature] += 1

    entries = {}
    for feature in ids:
        binding = binding_entries.get(feature)
        if binding is None:
            raise SystemExit(f'SOURCE_BINDINGS.json has no entry for {feature}')
        if binding['category'] == 'bound':
            sources = {'discovery': discovery[feature], 'bindings': binding['bindings']}
            category = 'bound'
        elif binding['category'] == 'native_only':
            sources = {'discovery': discovery[feature], 'native_only': binding['reason']}
            category = 'native_only'
        else:
            sources = {'discovery': discovery[feature]}
            category = 'discovery' if discovery[feature] else 'uncovered'
        entries[feature] = {'category': category, **sources}

    uncovered = [feature for feature, entry in entries.items() if entry['category'] == 'uncovered']
    counts = Counter(entry['category'] for entry in entries.values())
    result = {
        'schema_version': 2,
        'purpose': 'Traceable evidence per feature ID; coverage is not implementation or parity.',
        'feature_count': len(ids),
        'covered_count': len(ids) - len(uncovered),
        'categories': dict(sorted(counts.items())),
        'uncovered': uncovered,
        'unknown_feature_ids': sorted(unknown),
        'entries': entries,
    }
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f'features: {len(ids)}, covered: {len(ids) - len(uncovered)}, uncovered: {len(uncovered)}')
    print('categories: ' + json.dumps(result['categories'], ensure_ascii=False, sort_keys=True))
    if uncovered:
        print('uncovered: ' + ', '.join(uncovered))
    if unknown:
        print('unknown ids in index: ' + ', '.join(sorted(unknown)))


if __name__ == '__main__':
    main()
