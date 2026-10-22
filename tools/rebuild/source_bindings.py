#!/usr/bin/env python3
"""Bind every matrix feature ID to reviewed LG source evidence, or to "native only".

The behaviour index discovers routes/settings/pages/formats/skills. It does not cover
tools, model protocol fields, scheduler/retry, proofreading evaluation, project store,
compatibility storage or the runtime runner. This table fills that gap with one reviewed
(path, marker) pair per feature and verifies the marker really exists, so a feature is
never "covered" by a guess.

Native-only IDs are Android/LNT-side capabilities that have no LG source to point at
(signing upgrade, legacy LNT data migration, removal audit). They are recorded with a
reason instead of being silently dropped.

This is static evidence: a marker proves the reviewed file still contains the concept,
not that the runtime behaviour is verified.
"""
import argparse
import json
from pathlib import Path
import re

# (feature, path, marker, note). One reviewed binding per feature is enough; add more
# rows when a feature spans several files.
BINDINGS = [
    ("AG02", "src/backend/agent/tool-definition.ts", "define_agent_tool",
     "工具参数根 Schema 校验与定义入口"),
    ("AG05", "src/backend/agent/agent-input-queue.ts", "steer",
     "执行中引导的队列项占用与提交顺序"),
    ("AG08", "src/backend/agent/agent-chat-view.ts", "steer",
     "公开时间线把 steer 与普通输入分开展示"),
    ("AG15", "src/backend/agent/agent-model.ts", "usage",
     "按用途解析模型与用量读取"),
    ("AG16", "src/backend/agent/tools/batch-item-translation.ts", "define_agent_tool",
     "Agent 内批量翻译工具"),
    ("MO02", "src/backend/llm/llm-pi.ts", "api_format",
     "协议适配以 api_format 区分，不靠 provider 名称"),
    ("MO03", "src/domain/model.ts", "api_key",
     "认证类型与密钥字段定义"),
    ("MO07", "src/domain/model.ts", "thinking_level",
     "思考等级规范化"),
    ("MO08", "src/domain/model.ts", "extra_headers",
     "额外请求 headers/body 字段"),
    ("MO10", "src/backend/llm/llm-client.ts", "reasoning_tokens",
     "输入/思考/输出 token 分别读取"),
    ("OP02", "src/backend/batch-translation/core/batch-translation-runner.ts", "stopped",
     "长任务终态与停止；Android 前台 Service/通知仍是原生实现"),
    ("PR03", "src/backend/project/project-write-store.ts", "assetWrites",
     "资产结构写入与原始记录集中提交"),
    ("PR05", "src/domain/item.ts", "ITEM_STATUSES",
     "Item 状态集合与持久字段 Schema"),
    ("PR06", "src/backend/project/agent-workspace-fingerprint.ts", "agent_workspace_fingerprint",
     "对象指纹计算入口"),
    ("PR10", "src/backend/migration/project-migrations.ts", "empty_asset_file_type_migration",
     "工程 schema 迁移入口"),
    ("PR10", "src/backend/database/zstd-tool.ts", "zstd",
     "Zstd 压缩读写"),
    ("QL08", "src/shared/proofreading/proofreading-evaluator.ts", "warnings",
     "六类校对警告求值"),
    ("TR08", "src/backend/batch-translation/core/translation-request-scheduler.ts",
     "class TranslationRequestScheduler", "请求额度/并发调度"),
    ("TR09", "src/backend/batch-translation/planning/translation-retry.ts", "plan_translation_retry",
     "内容重试规划"),
    ("WS04", "src/backend/agent/tools/workspace-apply.ts", "define_agent_tool",
     "变更提交工具入口"),
    ("WS10", "src/backend/agent/tools/web-search.ts", "define_agent_tool",
     "联网搜索工具入口"),
    ("WS10", "src/backend/agent/tools/web-fetch.ts", "define_agent_tool",
     "网页获取工具入口"),
]

# No LG source can be cited: the capability lives in Android/LNT, not in the LG backend.
NATIVE_ONLY = {
    "OP06": "旧 LNT Room 数据迁移器与档案，LG 仓库没有 LNT 数据源",
    "OP07": "Android 签名升级、安装与备份回退边界，由平台实现",
    "OP08": "旧 LNT 实现/资源/依赖清理审计，属于本仓库退役工作",
}

MATRIX_ROW = re.compile(r'^\|\s*([A-Z]{2}\d{2})\s*\|', re.M)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lg', type=Path, required=True)
    parser.add_argument('--matrix', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = args.lg.resolve()
    matrix_ids = sorted(dict.fromkeys(MATRIX_ROW.findall(args.matrix.read_text())))
    problems = []
    bindings = {}
    for feature, path, marker, note in BINDINGS:
        if feature not in matrix_ids:
            problems.append(f'binding for unknown feature {feature}')
            continue
        target = root / path
        if not target.is_file():
            problems.append(f'{feature}: missing file {path}')
            continue
        text = target.read_text(errors='replace')
        line = next((number for number, value in enumerate(text.splitlines(), 1) if marker in value), None)
        if line is None:
            problems.append(f'{feature}: marker "{marker}" not found in {path}')
            continue
        bindings.setdefault(feature, []).append({
            'path': path, 'marker': marker, 'line': line, 'note': note,
        })
    entries = {}
    for feature in matrix_ids:
        if feature in bindings:
            entries[feature] = {'category': 'bound', 'bindings': bindings[feature]}
        elif feature in NATIVE_ONLY:
            entries[feature] = {'category': 'native_only', 'reason': NATIVE_ONLY[feature]}
        else:
            entries[feature] = {'category': 'unbound', 'bindings': []}
    for feature in sorted(bindings):
        if feature in NATIVE_ONLY:
            problems.append(f'{feature} is both bound and native_only')
    unbound = [feature for feature, entry in entries.items() if entry['category'] == 'unbound']
    result = {
        'schema_version': 1,
        'purpose': 'Reviewed static source binding per feature ID; marker presence is not runtime proof. '
                   'Features without a binding may still be covered by the behaviour index; see '
                   'FEATURE_COVERAGE.json for the combined view.',
        'feature_count': len(matrix_ids),
        'bound_count': len(bindings),
        'native_only_count': len(NATIVE_ONLY),
        'without_binding': unbound,
        'entries': entries,
    }
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f'features: {len(matrix_ids)}, bound: {len(bindings)}, native_only: {len(NATIVE_ONLY)}, '
          f'without_binding: {len(unbound)}')
    if problems:
        raise SystemExit('\n'.join(problems))


if __name__ == '__main__':
    main()
