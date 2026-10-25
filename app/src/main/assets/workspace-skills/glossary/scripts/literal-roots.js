// Port of LG literal-matcher.ts + glossary/scripts/match-literals.mjs.
// Type annotations removed; grapheme/normalization use the bounded native Unicode host.
const GRAPHEME_SEGMENTER = { segment: function(text) { return lg.unicode.segments(text); } };

/** 全仓字面量匹配的唯一规范化规则；大小写标志只控制 casefold。 */
function normalize_literal_text(text, case_sensitive) {
  const normalized = lg.unicode.normalize(text, "NFKC");
  return case_sensitive ? normalized : fold_literal_text(normalized);
}

/** 在 NFKC 结果上统一德语展开及希腊词尾变体。 */
function fold_literal_text(normalized) {
  return normalized.split("ẞ").join("ss").split("ß").join("ss").toLowerCase().split("ς").join("σ");
}


const MIN_FORM_COUNT = 2; // 公共片段至少涉及两个不同词形

/** 已确认相关词形的公共连续片段，按可见字符长度升序返回。 */
function deriveCommonLiteralRoots(forms) {
  const normalizedForms = forms.map((form) => normalize_literal_text(form, false));
  if (new Set(normalizedForms).size < MIN_FORM_COUNT) {
    throw new Error("forms must contain at least two distinct forms");
  }

  const segmenter = { segment: function(text) { return lg.unicode.segments(text); } };
  // 后续长度与包含判断都以用户可见字符为单位。
  const segment = (text) => [...segmenter.segment(text)].map((part) => part.segment);
  const baseGraphemes = segment(forms[0]);
  const normalizedFormGraphemes = normalizedForms.map(segment);
  const candidatesByNormalized = new Map();
  // 数组级匹配避免把组合字符或代理对拆开比较。
  const containsSequence = (form, root) => {
    for (let start = 0; start <= form.length - root.length; start += 1) {
      if (root.every((grapheme, offset) => form[start + offset] === grapheme)) return true;
    }
    return false;
  };

  // ponytail: 最坏 O(N × L⁴)，只适合短术语；基准证明不足时再引入子串索引。
  for (let start = 0; start < baseGraphemes.length; start += 1) {
    for (let end = start + 1; end <= baseGraphemes.length; end += 1) {
      const root = baseGraphemes.slice(start, end).join("");
      const normalizedKey = normalize_literal_text(root, false);
      const normalizedRoot = segment(normalizedKey);
      if (
        normalizedKey.trim() === "" ||
        candidatesByNormalized.has(normalizedKey) ||
        !normalizedFormGraphemes.slice(1).every((form) => containsSequence(form, normalizedRoot))
      ) {
        continue;
      }
      candidatesByNormalized.set(normalizedKey, {
        root,
        grapheme_length: end - start,
      });
    }
  }

  return {
    // Map 保留发现顺序，稳定排序让等长候选沿用该顺序。
    candidates: [...candidatesByNormalized.values()].sort(
      (left, right) => left.grapheme_length - right.grapheme_length,
    ),
  };
}

const options = JSON.parse(lg.arguments.input_json || "{}");
if (!Array.isArray(options.forms)) throw new Error("Expected forms array");
const result = deriveCommonLiteralRoots(options.forms);
lg.emitArtifact({path:"glossary-literal-roots.json",mediaType:"application/json",content:JSON.stringify(result)});
lg.log(JSON.stringify({report:"glossary-literal-roots.json"}));
return 0;
