#!/usr/bin/env python3
"""Map the 98 feature IDs to discovered LG entries and list uncovered IDs.

Coverage here means "at least one discovery entry was mapped to this ID", not that the
feature is implemented or audited. Inputs are the static matrix and the audit output.
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
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()

    ids = sorted(dict.fromkeys(MATRIX_ROW.findall(args.matrix.read_text())))
    entries = json.loads(args.audit.read_text())['entries']
    coverage = {feature: [] for feature in ids}
    unknown = Counter()
    for entry in entries:
        for feature in entry.get('candidate_features', []):
            if feature in coverage:
                coverage[feature].append({
                    'kind': entry['kind'],
                    'key': entry['key'],
                    'status': entry['review']['status'],
                })
            else:
                unknown[feature] += 1

    uncovered = [feature for feature in ids if not coverage[feature]]
    result = {
        'schema_version': 1,
        'purpose': 'Discovery coverage only: one mapped entry does not implement or verify a feature.',
        'feature_count': len(ids),
        'covered_count': len(ids) - len(uncovered),
        'uncovered': uncovered,
        'unknown_feature_ids': sorted(unknown),
        'by_kind': {
            feature: dict(sorted(Counter(item['kind'] for item in items).items()))
            for feature, items in coverage.items() if items
        },
        'entries': {feature: items for feature, items in coverage.items()},
    }
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f'features: {len(ids)}, covered: {len(ids) - len(uncovered)}, uncovered: {len(uncovered)}')
    if uncovered:
        print('uncovered: ' + ', '.join(uncovered))
    if unknown:
        print('unknown ids in index: ' + ', '.join(sorted(unknown)))


if __name__ == '__main__':
    main()
