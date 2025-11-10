package com.example.attempt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.math.BigDecimal;
import java.math.RoundingMode;

public class Fill {

    public static class Rule {
        public String mid;          
        public String id;        
        public String valueType;    
        public Boolean isMust;
        public Integer dimension;   
        public List<Object> scale;
        public String dependency;
        public String exclusion;
        public String extend;
    }

    private static final int MAX_VALUE_RETRIES = 5;  // 单个值的重试次数
    private static final int MAX_TEMPLATE_RETRIES = 5; // 整个模板的重试次数

    private static Map<String, Map<String, Rule>> indexByMidId(List<Rule> rules) {
        Map<String, Map<String, Rule>> idx = new HashMap<>();
        for (Rule r : rules) {
            idx.computeIfAbsent(r.mid.toUpperCase(), k -> new HashMap<>())
               .put(r.id.toUpperCase(), r);
        }
        return idx;
    }

    private static final Pattern MID_AT_LINE_START =
            Pattern.compile("^\\s*(&[A-Z0-9_]+)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern PLACEHOLDER_IN_LINE =
            Pattern.compile("\\b([A-Z0-9_]+)=\\[\\[V\\]\\]", Pattern.CASE_INSENSITIVE);

    private static final Pattern PLACEHOLDER_C_IN_LINE =
            Pattern.compile("\\b([A-Z0-9_]+)=\\[\\[C\\]\\]", Pattern.CASE_INSENSITIVE);

    // 识别引用格式 PARAM=<MODULE_XX.YY>
    private static final Pattern PLACEHOLDER_REF_IN_LINE =
            Pattern.compile("\\b([A-Z0-9_]+)=<([A-Z0-9_]+)\\.([A-Z0-9_]+)>", Pattern.CASE_INSENSITIVE);

    private static final Map<String, Integer> DEFAULT_DIM_BY_ID = Map.of(
            "IJK", 3,
            "GVEC", 3,
            "XB", 6
    );
    
    public static void main(String[] args) throws Exception {
        Path jsonPath    = Path.of("../attempt/src-gen/semo.json");
        Path templateTxt = Path.of("../attempt/template.txt");
        Path outPath     = Path.of("../attempt/filled.txt");

        fill(jsonPath, templateTxt, outPath);
        System.out.println("已写出: " + outPath.toAbsolutePath());
    }

    public static void fill(Path jsonPath, Path templatePath, Path outPath) throws IOException {
        ObjectMapper om = new ObjectMapper();
        List<Rule> rules = om.readValue(Files.readAllBytes(jsonPath), new TypeReference<List<Rule>>() {});
        Map<String, Map<String, Rule>> idx = indexByMidId(rules);

        int templateRetryCount = 0;
        boolean templateSuccess = false;
        
        while (!templateSuccess && templateRetryCount < MAX_TEMPLATE_RETRIES) {
            Map<String, String> valueCache = new HashMap<>();
            Set<String> generatedKeys = new HashSet<>();
            List<String> out = new ArrayList<>();
            
            try {
                List<String> lines = Files.readAllLines(templatePath, StandardCharsets.UTF_8);
                for (String line : lines) {
                    // 为每一行创建独立的 generatedKeys，避免跨行污染
                    Set<String> lineGeneratedKeys = new HashSet<>();
                    String processedLine = replaceLine(line, idx, valueCache, lineGeneratedKeys);
                    // 将本行生成的 key 添加到全局 generatedKeys（用于依赖检查）
                    generatedKeys.addAll(lineGeneratedKeys);
                    // 后处理：移除所有未填充的占位符（保留 [[V]] 或 [[C]] 的参数）
                    processedLine = removeUnfilledPlaceholders(processedLine);
                    out.add(processedLine);
                }
                templateSuccess = true;
                Files.write(outPath, out, StandardCharsets.UTF_8);
            } catch (ExclusionConflictException e) {
                templateRetryCount++;
                System.out.println("⚠️  Warning: " + e.getMessage());
                System.out.println("    Regenerating template... (Attempt " + templateRetryCount + "/" + MAX_TEMPLATE_RETRIES + ")");
                
                if (templateRetryCount >= MAX_TEMPLATE_RETRIES) {
                    System.err.println("❌ ERROR: Failed to resolve exclusion conflicts after " + MAX_TEMPLATE_RETRIES + " template retries.");
                    throw new RuntimeException("Exclusion conflict unresolved", e);
                }
            }
        }
    }
    
    /**
     * 移除行中所有未填充的占位符（包含 [[V]] 或 [[C]] 的参数）
     * 例如：HRRPUA=[[V]] 会被移除
     */
    private static String removeUnfilledPlaceholders(String line) {
        // 移除 KEY=[[V]] 或 KEY=[[C]] 模式的参数
        String result = line.replaceAll(",\\s*[A-Z0-9_]+=\\[\\[[VC]\\]\\]", "");
        result = result.replaceAll("\\s+[A-Z0-9_]+=\\[\\[[VC]\\]\\],", "");
        result = result.replaceAll("\\s+[A-Z0-9_]+=\\[\\[[VC]\\]\\]", "");
        
        // 清理多余的逗号和空格
        result = result.replaceAll(",\\s*,", ",");
        result = result.replaceAll(",\\s*/", " /");
        
        return result;
    }
    
    static class ExclusionConflictException extends RuntimeException {
        public ExclusionConflictException(String message) {
            super(message);
        }
    }
    
    private static String replaceLine(String line, Map<String, Map<String, Rule>> idx, 
                                     Map<String, String> valueCache, Set<String> generatedKeys) {
        Matcher m = MID_AT_LINE_START.matcher(line);
        if (!m.find()) return line; 
        String mid = m.group(1).toUpperCase();

        // 处理顺序：<MODULE_XX.YY> -> [[C]] -> [[V]]
        
        // 第一步：处理引用格式 PARAM=<MODULE_XX.YY>（从引用的模块中获取值）
        StringBuffer sb = new StringBuffer();
        Matcher phr = PLACEHOLDER_REF_IN_LINE.matcher(line);
        while (phr.find()) {
            String paramName = phr.group(1).toUpperCase();  // 参数名，如 SURF_ID
            String refModule = phr.group(2).toUpperCase();  // 引用的模块，如 MODULE_SURF
            String refParam = phr.group(3).toUpperCase();   // 引用的参数，如 ID
            
            // 构造引用路径：&MODULE_SURF.ID
            String refMid = "&" + refModule.replace("MODULE_", "");
            String refKey = refMid + "." + refParam;
            
            // 从缓存中查找引用值（查找所有带 UUID 的 key，选择最后一个匹配项）
            String foundValue = null;
            for (String cachedKey : valueCache.keySet()) {
                if (extractBaseKey(cachedKey).equalsIgnoreCase(refKey)) {
                    foundValue = valueCache.get(cachedKey);
                    // 不 break，继续查找，最终会得到最后一个匹配的值
                }
            }
            
            String replacement;
            if (foundValue != null) {
                // 找到引用值，直接使用（添加引号如果需要）
                Rule currentRule = Optional.ofNullable(idx.get(mid)).map(map -> map.get(paramName)).orElse(null);
                String valueType = (currentRule != null) ? currentRule.valueType : "string";
                replacement = paramName + "=" + quoteIfNeeded(foundValue, valueType);
            } else {
                // 未找到引用值，尝试生成（如果当前参数有规则）
                Rule rule = Optional.ofNullable(idx.get(mid)).map(map -> map.get(paramName)).orElse(null);
                if (rule == null) {
                    // 没有规则，保留原样
                    replacement = phr.group(0);
                } else if (shouldGenerateParam(rule)) {
                    // 根据 isMust 决定是否生成（会触发依赖生成）
                    String uniqueKey = mid + "." + paramName + "#" + UUID.randomUUID().toString();
                    replacement = paramName + "=" + generateValue(rule, paramName, idx, valueCache, generatedKeys, uniqueKey);
                } else {
                    // 不生成，保留占位符
                    replacement = phr.group(0);
                }
            }
            phr.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        phr.appendTail(sb);
        
        // 第二步：处理 [[C]] 占位符（常量）
        String result = sb.toString();
        Matcher phc = PLACEHOLDER_C_IN_LINE.matcher(result);
        sb = new StringBuffer();
        while (phc.find()) {
            String id = phc.group(1).toUpperCase();
            Rule rule = Optional.ofNullable(idx.get(mid)).map(map -> map.get(id)).orElse(null);
            String replacement;
            if (rule == null) {
                replacement = phc.group(0);
            } else if (shouldGenerateParam(rule)) {
                // [[C]] 是常量，也使用 UUID（允许多个不同的常量值）
                String uniqueKey = mid + "." + id + "#" + UUID.randomUUID().toString();
                replacement = id + "=" + generateValue(rule, id, idx, valueCache, generatedKeys, uniqueKey);
            } else {
                replacement = phc.group(0);
            }
            phc.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        phc.appendTail(sb);
        
        // 第三步：处理 [[V]] 占位符（普通变量）
        result = sb.toString();
        Matcher ph = PLACEHOLDER_IN_LINE.matcher(result);
        sb = new StringBuffer();
        while (ph.find()) {
            String id = ph.group(1).toUpperCase();
            Rule rule = Optional.ofNullable(idx.get(mid)).map(map -> map.get(id)).orElse(null);
            String replacement;
            if (rule == null) {
                replacement = ph.group(0);
            } else if (shouldGenerateParam(rule)) {
                // 检查是否会被其他参数依赖
                boolean isReferenced = isParameterReferenced(mid, id, idx);
                String key;
                if (isReferenced) {
                    // 如果会被引用，使用原始 key（不带 UUID）
                    key = mid + "." + id;
                } else {
                    // 否则使用 UUID key，允许多值
                    key = mid + "." + id + "#" + UUID.randomUUID().toString();
                }
                replacement = id + "=" + generateValue(rule, id, idx, valueCache, generatedKeys, key);
            } else {
                replacement = ph.group(0);
            }
            ph.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        ph.appendTail(sb);
        
        // 第四步：追加通过 Extend 生成的参数（不在模板中的参数）
        String resultWithExtends = appendExtendedParams(mid, sb.toString(), idx, valueCache, generatedKeys);
        
        return resultWithExtends;
    }
    
    /**
     * 追加通过 Extend 生成的参数到行中
     * @param mid 模块ID（如 &MESH）
     * @param line 当前行
     * @param idx 规则索引
     * @param valueCache 值缓存
     * @param generatedKeys 已生成的键集合
     * @return 追加扩展参数后的行
     */
    private static String appendExtendedParams(String mid, String line,
                                              Map<String, Map<String, Rule>> idx,
                                              Map<String, String> valueCache,
                                              Set<String> generatedKeys) {
        // 找出该模块中所有已生成但不在当前行中的参数（这些是通过 Extend 添加的）
        List<String> extendedParams = new ArrayList<>();
        
        for (String key : generatedKeys) {
            if (key.startsWith(mid + ".")) {
                // 提取参数 ID（去掉 UUID 部分）
                String keyWithoutMid = key.substring(mid.length() + 1);
                String paramId = keyWithoutMid.contains("#") 
                    ? keyWithoutMid.substring(0, keyWithoutMid.indexOf("#"))
                    : keyWithoutMid;
                
                // 检查该参数是否已经在行中（通过正则匹配 PARAM_ID=）
                Pattern paramPattern = Pattern.compile("\\b" + paramId + "=", Pattern.CASE_INSENSITIVE);
                if (!paramPattern.matcher(line).find()) {
                    // 不在行中，说明是扩展参数
                    String value = valueCache.get(key);
                    if (value != null) {
                        // 获取规则以确定是否需要引号
                        Rule rule = Optional.ofNullable(idx.get(mid))
                                           .map(map -> map.get(paramId))
                                           .orElse(null);
                        String formattedValue = (rule != null) 
                            ? quoteIfNeeded(value, rule.valueType)
                            : value;
                        
                        extendedParams.add(paramId + "=" + formattedValue);
                    }
                }
            }
        }
        
        // 如果有扩展参数，插入到行尾的 "/" 之前
        if (!extendedParams.isEmpty()) {
            String paramsStr = String.join(", ", extendedParams);
            // 在 "/" 之前插入（可能前面有逗号或空格）
            return line.replaceFirst("\\s*/\\s*$", ", " + paramsStr + " /");
        }
        
        return line;
    }
    
    /**
     * 决定是否生成参数（不考虑 isMust 字段）
     * @param rule JSON 规则
     * @return true 始终生成参数
     */
    private static boolean shouldGenerateParam(Rule rule) {
        // 始终生成参数，不考虑 isMust 字段
        return true;
    }
    
    /**
     * 检查某个参数是否会被其他参数依赖（引用）
     * @param mid 模块 ID（如 &SURF）
     * @param id 参数 ID（如 ID）
     * @param idx 规则索引
     * @return true 如果该参数会被其他参数依赖
     */
    private static boolean isParameterReferenced(String mid, String id, Map<String, Map<String, Rule>> idx) {
        String targetKey = mid.substring(1) + "." + id; // 移除 & 前缀，如 "SURF.ID"
        
        // 遍历所有规则，检查是否有 dependency 指向该参数
        for (Map<String, Rule> moduleRules : idx.values()) {
            for (Rule rule : moduleRules.values()) {
                if (rule.dependency != null && !rule.dependency.isEmpty()) {
                    String dep = rule.dependency.replaceAll("^<(ref:|dependency:)?", "").replaceAll(">$", "");
                    // 检查依赖路径是否匹配（忽略 MODULE_ 前缀）
                    String normalizedDep = dep.replace("MODULE_", "");
                    if (normalizedDep.equalsIgnoreCase(targetKey)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    // 生成值的主入口，按照流程图处理：初始化 -> 依赖检查 -> 扩展检查 -> 排斥检查
    private static String generateValue(Rule r, String id, Map<String, Map<String, Rule>> idx, 
                                       Map<String, String> valueCache, Set<String> generatedKeys,
                                       String currentKey) throws ExclusionConflictException {
        // 1. 依赖检查（Dependency）：如果依赖别的 key，先为依赖项生成值
        if (r.dependency != null && !r.dependency.isEmpty()) {
            String refPath = r.dependency;
            refPath = refPath.replaceAll("^<(ref:|dependency:)?", "").replaceAll(">$", "");
            String[] parts = refPath.split("\\.");
            if (parts.length == 2) {
                String refMid = "&" + parts[0].replace("MODULE_", "");
                String refId = parts[1];
                String refKey = refMid + "." + refId;
                
                // 检查缓存：查找所有带 UUID 的 key，找到第一个匹配的基础 key
                String foundValue = null;
                for (String cachedKey : valueCache.keySet()) {
                    if (extractBaseKey(cachedKey).equalsIgnoreCase(refKey)) {
                        foundValue = valueCache.get(cachedKey);
                        break;
                    }
                }
                
                if (foundValue != null) {
                    generatedKeys.add(currentKey);
                    return quoteIfNeeded(foundValue, r.valueType);
                }
                
                // 如果依赖项未生成，先为依赖项生成值（递归，使用原始 key 不带 UUID）
                Rule refRule = Optional.ofNullable(idx.get(refMid)).map(map -> map.get(refId)).orElse(null);
                if (refRule != null) {
                    String depValue = generateValue(refRule, refId, idx, valueCache, generatedKeys, refKey);
                    String pureValue = depValue.replaceAll("^'|'$", "");
                    valueCache.put(refKey, pureValue);
                    generatedKeys.add(currentKey);
                    return quoteIfNeeded(pureValue, r.valueType);
                }
            }
            return "[[C]]";
        }
        
        // 2. 检查当前 key 是否已有缓存值
        if (valueCache.containsKey(currentKey)) {
            String cachedValue = valueCache.get(currentKey);
            generatedKeys.add(currentKey);
            return quoteIfNeeded(cachedValue, r.valueType);
        }
        
        // 3. 排斥检查（提前检查键级别的排斥）
        // 如果当前 key 因为排斥规则不能生成，直接返回占位符
        if (!checkKeyLevelExclusion(r, currentKey, generatedKeys) ||
            !checkReverseExclusion(r, currentKey, valueCache, generatedKeys, idx)) {
            System.out.println("  ⊗ Skipping " + currentKey + " due to exclusion conflict (keeping placeholder)");
            return "[[V]]"; // 保留占位符，不生成值
        }
        
        // 4. 生成新值（带值级别的排斥检查）
        String generatedValue = null;
        int retryCount = 0;
        
        while (retryCount < MAX_VALUE_RETRIES) {
            String candidateValue = randomValueFor(r, id, idx);
            String pureValue = candidateValue.replaceAll("^'|'$", "");
            
            // 值级别排斥检查：检查当前值是否与已生成的其他 key 的特定值冲突
            if (checkValueLevelExclusion(r, pureValue, valueCache, currentKey)) {
                // 通过排斥检查
                generatedValue = candidateValue;
                valueCache.put(currentKey, pureValue);
                generatedKeys.add(currentKey);
                break;
            } else {
                // 冲突，重试生成不同的值
                retryCount++;
                System.out.println("  ↻ Value exclusion conflict for " + currentKey + " = " + pureValue + " (retry " + retryCount + "/" + MAX_VALUE_RETRIES + ")");
            }
        }
        
        if (generatedValue == null) {
            // 超过最大重试次数，保留占位符
            System.out.println("  ⊗ Failed to generate valid value for " + currentKey + " after " + MAX_VALUE_RETRIES + " retries (keeping placeholder)");
            return "[[V]]";
        } 
        
        // 5. 扩展检查（Extend）：如果当前字段有 extend 规则，检查是否触发扩展
        String pureValue = generatedValue.replaceAll("^'|'$", "");
        if (r.extend != null && !r.extend.isEmpty()) {
            processExtend(r, pureValue, idx, valueCache, generatedKeys);
        }
        
        return generatedValue;
    }
    
    /**
     * 键级别排斥检查：检查当前 key 是否因为排斥规则而不能生成（不考虑具体值）
     * @return true 如果可以生成，false 如果不能生成
     */
    private static boolean checkKeyLevelExclusion(Rule r, String currentKey, Set<String> generatedKeys) {
        if (r.exclusion == null || r.exclusion.isEmpty()) {
            return true; // 没有排斥规则
        }
        
        String exclRule = r.exclusion;
        exclRule = exclRule.replaceAll("^<clusion:", "").replaceAll(">$", "").trim();
        
        // 只处理键排斥（不带值的排斥）
        if (!exclRule.contains(":")) {
            String[] parts = exclRule.split("\\.");
            if (parts.length == 2) {
                String exclMid = "&" + parts[0].replace("MODULE_", "");
                String exclId = parts[1];
                String exclKey = exclMid + "." + exclId;
                
                // 检查排斥的 key 是否已生成（忽略 UUID 部分）
                for (String existingKey : generatedKeys) {
                    String baseKey = extractBaseKey(existingKey);
                    if (baseKey.equalsIgnoreCase(exclKey)) {
                        System.out.println("  ✗ Key exclusion: " + extractBaseKey(currentKey) + " cannot coexist with " + exclKey);
                        return false; // 键冲突，不能生成
                    }
                }
            }
        }
        
        return true; // 没有键级别冲突
    }
    
    /**
     * 值级别排斥检查：检查当前 key 的特定值是否违反排斥规则
     * @return true 如果没有冲突，false 如果有冲突
     */
    private static boolean checkValueLevelExclusion(Rule r, String currentValue,
                                                    Map<String, String> valueCache,
                                                    String currentKey) {
        if (r.exclusion == null || r.exclusion.isEmpty()) {
            return true; // 没有排斥规则
        }
        
        String exclRule = r.exclusion;
        exclRule = exclRule.replaceAll("^<clusion:", "").replaceAll(">$", "").trim();
        
        // 只处理值排斥（带值的排斥）
        if (exclRule.contains(":")) {
            String[] parts = exclRule.split(":", 2);
            if (parts.length == 2) {
                String keyPath = parts[0].trim();
                String excludedValue = parts[1].trim();
                
                String[] keyParts = keyPath.split("\\.");
                if (keyParts.length == 2) {
                    String exclMid = "&" + keyParts[0].replace("MODULE_", "");
                    String exclId = keyParts[1];
                    String exclKey = exclMid + "." + exclId;
                    
                    // 检查排斥的 key 是否存在且值等于 excludedValue（需要查找所有带 UUID 的 key）
                    for (String cachedKey : valueCache.keySet()) {
                        if (extractBaseKey(cachedKey).equalsIgnoreCase(exclKey)) {
                            String exclValue = valueCache.get(cachedKey);
                            if (exclValue.equals(excludedValue)) {
                                System.out.println("  ✗ Value exclusion: " + extractBaseKey(currentKey) + " cannot coexist with " + exclKey + "=" + excludedValue);
                                return false; // 值冲突
                            }
                        }
                    }
                }
            }
        }
        
        return true; // 没有值级别冲突
    }
    
    /**
     * 反向排斥检查：检查已生成的其他 key 是否声明了排斥当前 key
     * @return true 如果没有冲突，false 如果有冲突
     */
    private static boolean checkReverseExclusion(Rule currentRule, String currentKey,
                                                 Map<String, String> valueCache,
                                                 Set<String> generatedKeys,
                                                 Map<String, Map<String, Rule>> idx) {
        // 遍历所有已生成的 key，检查它们是否排斥当前 key
        for (String existingKey : generatedKeys) {
            // 提取基础 key（去掉 UUID）
            String baseExistingKey = extractBaseKey(existingKey);
            
            // 解析 existingKey (格式: &MID.ID 或 &MID.ID#UUID)
            String[] parts = baseExistingKey.split("\\.", 2);
            if (parts.length != 2) continue;
            
            String existingMid = parts[0];
            String existingId = parts[1];
            
            // 获取已存在 key 的规则
            Rule existingRule = Optional.ofNullable(idx.get(existingMid))
                                       .map(map -> map.get(existingId))
                                       .orElse(null);
            
            if (existingRule == null || existingRule.exclusion == null || existingRule.exclusion.isEmpty()) {
                continue;
            }
            
            String exclRule = existingRule.exclusion;
            exclRule = exclRule.replaceAll("^<clusion:", "").replaceAll(">$", "").trim();
            
            // 检查已存在的 key 是否排斥当前 key
            if (exclRule.contains(":")) {
                // 值排斥：检查是否排斥当前 key 的某个值
                String[] exclParts = exclRule.split(":", 2);
                if (exclParts.length == 2) {
                    String keyPath = exclParts[0].trim();
                    String excludedValue = exclParts[1].trim();
                    
                    String[] keyParts = keyPath.split("\\.");
                    if (keyParts.length == 2) {
                        String exclMid = "&" + keyParts[0].replace("MODULE_", "");
                        String exclId = keyParts[1];
                        String exclKey = exclMid + "." + exclId;
                        
                        if (extractBaseKey(currentKey).equalsIgnoreCase(exclKey)) {
                            String currentValue = valueCache.get(currentKey);
                            if (currentValue != null && currentValue.equals(excludedValue)) {
                                System.out.println("  ✗ Reverse exclusion conflict: " + baseExistingKey + " excludes " + extractBaseKey(currentKey) + "=" + excludedValue);
                                return false;
                            }
                        }
                    }
                }
            } else {
                // key 排斥：检查是否排斥当前 key
                String[] exclParts = exclRule.split("\\.");
                if (exclParts.length == 2) {
                    String exclMid = "&" + exclParts[0].replace("MODULE_", "");
                    String exclId = exclParts[1];
                    String exclKey = exclMid + "." + exclId;
                    
                    if (extractBaseKey(currentKey).equalsIgnoreCase(exclKey)) {
                        System.out.println("  ✗ Reverse exclusion conflict: " + baseExistingKey + " excludes " + extractBaseKey(currentKey));
                        return false;
                    }
                }
            }
        }
        
        return true; // 没有反向冲突
    }
    
    /**
     * 提取基础 key（去掉 UUID 部分）
     * @param key 完整的 key（可能包含 #UUID）
     * @return 基础 key（&MID.ID）
     */
    private static String extractBaseKey(String key) {
        if (key.contains("#")) {
            return key.substring(0, key.indexOf("#"));
        }
        return key;
    }
    
    /**
     * 处理 Extend 逻辑：如果当前值匹配触发值，则在目标模块中生成扩展参数
     * @param r 当前规则
     * @param currentValue 当前生成的值（不带引号）
     * @param idx 规则索引
     * @param valueCache 值缓存
     * @param generatedKeys 已生成的键集合
     */
    private static void processExtend(Rule r, String currentValue,
                                      Map<String, Map<String, Rule>> idx,
                                      Map<String, String> valueCache,
                                      Set<String> generatedKeys) {
        String extendRule = r.extend;
        if (extendRule == null || extendRule.isEmpty()) return;
        
        // 解析格式：<td:triggerValue:MODULE_M.C>
        // 例如：<td:1:MODULE_MESH.SPECIAL_CONFIG> 或 <td:'SKt2':MODULE_SURF.EXTRA>
        // 先移除 <td: 和 >
        extendRule = extendRule.replaceAll("^<td:", "").replaceAll(">$", "").trim();
        
        String[] parts = extendRule.split(":", 2);
        if (parts.length != 2) {
            System.out.println("  ⚠ Invalid extend format: " + extendRule);
            return;
        }
        
        String triggerValue = parts[0].trim().replaceAll("^'|'$", "");
        String targetPath = parts[1].trim();
        
        // 调试日志：显示 Extend 规则解析结果
        System.out.println("  ⚙ Extend check: current='" + currentValue + "', trigger='" + triggerValue + "', target='" + targetPath + "'");
        
        // 检查当前值是否触发扩展（支持数值比较）
        if (!valuesMatch(currentValue, triggerValue)) {
            System.out.println("  ○ Extend not triggered: " + currentValue + " ≠ " + triggerValue);
            return;
        }
        
        // 解析目标路径：MODULE_M.C
        String[] pathParts = targetPath.split("\\.");
        if (pathParts.length != 2) {
            System.out.println("  ⚠ Invalid extend target path: " + targetPath);
            return;
        }
        
        String targetMid = "&" + pathParts[0].replace("MODULE_", "");
        String targetId = pathParts[1];
        String targetBaseKey = targetMid + "." + targetId;
        
        // 检查目标参数是否已存在（使用原始 key 不带 UUID）
        boolean alreadyExists = false;
        for (String existingKey : generatedKeys) {
            if (extractBaseKey(existingKey).equalsIgnoreCase(targetBaseKey)) {
                alreadyExists = true;
                break;
            }
        }
        
        if (alreadyExists) {
            System.out.println("  ⊕ Extend skipped: " + targetBaseKey + " already exists");
            return;
        }
        
        // 获取目标参数的规则
        Rule targetRule = Optional.ofNullable(idx.get(targetMid))
                                   .map(map -> map.get(targetId))
                                   .orElse(null);
        
        if (targetRule == null) {
            System.out.println("  ⚠ Extend failed: Rule not found for " + targetBaseKey);
            return;
        }
        
        // 为目标参数生成值（递归调用 generateValue），使用原始 key（不带 UUID）
        System.out.println("  ⊕ Extend triggered: Adding " + targetBaseKey + " (trigger value: " + triggerValue + ")");
        try {
            String extendedValue = generateValue(targetRule, targetId, idx, valueCache, generatedKeys, targetBaseKey);
            // 值已在 generateValue 中缓存到 valueCache 和 generatedKeys
            System.out.println("  ✓ Extended parameter generated: " + targetBaseKey + " = " + extendedValue);
        } catch (Exception e) {
            System.out.println("  ⚠ Extend failed for " + targetBaseKey + ": " + e.getMessage());
        }
    }
    
    /**
     * 比较两个值是否匹配（支持数值比较）
     * @param value1 第一个值
     * @param value2 第二个值
     * @return true 如果匹配
     */
    private static boolean valuesMatch(String value1, String value2) {
        // 先尝试字符串比较
        if (value1.equals(value2)) {
            return true;
        }
        
        // 尝试数值比较（处理 0 vs 0.0 的情况）
        try {
            double num1 = Double.parseDouble(value1);
            double num2 = Double.parseDouble(value2);
            return Math.abs(num1 - num2) < 0.0001; // 浮点数比较用误差范围
        } catch (NumberFormatException e) {
            // 不是数值，返回字符串比较结果
            return false;
        }
    }
    
    // 原有的随机值生成逻辑（不再处理 dependency）
    private static String randomValueFor(Rule r, String id, Map<String, Map<String, Rule>> idx) {
        
        int dim = (r.dimension != null && r.dimension > 0)
                ? r.dimension
                : DEFAULT_DIM_BY_ID.getOrDefault(id.toUpperCase(), 1);

        if (r.valueType != null && r.valueType.trim().equalsIgnoreCase("<enum>")) {
            String choice = randomFromStrings(r.scale);
            return quoteIfNeeded(choice, r.valueType);
        }

     // 补全 TRUE/FALSE 范围的布尔类型处理，支持大小写
        if (r.scale != null && r.scale.size() == 2) {
            String s1 = String.valueOf(r.scale.get(0)).toLowerCase();
            String s2 = String.valueOf(r.scale.get(1)).toLowerCase();
            if ((s1.equals("true") && s2.equals("false")) || (s1.equals("false") && s2.equals("true"))) {
                boolean val = ThreadLocalRandom.current().nextBoolean();
                // 保持原 scale 的大小写
                String result = val ? String.valueOf(r.scale.get(0)) : String.valueOf(r.scale.get(1));
                // 如果两个 scale 都是 true/false，只是大小写不同，随机选一个
                if (!result.equalsIgnoreCase("true") && !result.equalsIgnoreCase("false")) {
                    result = val ? "true" : "false";
                }
                return quoteIfNeeded(result, r.valueType);
            }
        }
        
        
        
        if (isNumericRange(r.scale)) {
            double min = toDouble(r.scale.get(0));
            double max = toDouble(r.scale.get(1));
            if (dim > 1) {
                List<String> arr = new ArrayList<>(dim);
                for (int i = 0; i < dim; i++) {
                    arr.add(formatNumber(randomBetween(min, max, r.valueType)));
                }
                return String.join(", ", arr);
            } else {
                return formatNumber(randomBetween(min, max, r.valueType));
            }
        }

        if (r.scale != null && !r.scale.isEmpty()) {
            Object pick = r.scale.get(ThreadLocalRandom.current().nextInt(r.scale.size()));
            if (pick instanceof Number) {
                return formatNumber(((Number) pick).doubleValue());
            } else {
                return quoteIfNeeded(String.valueOf(pick).replace("'", ""), r.valueType);
            }
        }
        return "[[V]]";
    }
    private static boolean isNumericRange(List<Object> scale) {
        if (scale == null || scale.size() != 2) return false;
        try { toDouble(scale.get(0)); toDouble(scale.get(1)); return true; }
        catch (NumberFormatException e) { return false; }
    }
    private static double toDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        return Double.parseDouble(String.valueOf(o).replace("'", ""));
    }
    private static double randomBetween(double min, double max, String valueType) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (valueType != null && valueType.toLowerCase().contains("integer"))
            return r.nextInt((int)Math.round(min), (int)Math.round(max) + 1);
        return min + r.nextDouble() * (max - min);
    }
    private static String formatNumber(double v) {
        BigDecimal bd = BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
        return bd.stripTrailingZeros().toPlainString();
    }
    private static String randomFromStrings(List<Object> scale) {
        List<String> strs = new ArrayList<>();
        if (scale != null) for (Object o : scale) strs.add(String.valueOf(o).replace("'", ""));
        if (strs.isEmpty()) return "";
        return strs.get(ThreadLocalRandom.current().nextInt(strs.size()));
    }
    //q
    private static String quoteIfNeeded(String s, String valueType) {
        if (valueType != null && 
            (valueType.toLowerCase().contains("string") || 
             valueType.toLowerCase().contains("<enum>"))) {
            return "'" + s + "'";
        }
        return s;
    }
    
    private static String quoteIfNeeded(String s) {
        return s;
    }
}
