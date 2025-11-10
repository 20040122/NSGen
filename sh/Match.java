package com.example.attempt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Match {
    private static final Random RANDOM = new Random();
    
    // temo.json 的结构
    public static class TemplateEntry {
        public String name;
        public String val;
    }
    
    // demo.json 的结构
    public static class ModuleConstraint {
        public String name;
        public boolean required;
        public int min;
        public int max;
        public Integer val_min;
        public Integer val_max;
    }
    
    // semo.json 的结构
    public static class ParameterSpec {
        public String mid;
        public String id;
        public String valueType;
        public boolean isMust;
        public Integer dimension;
        public Object scale;
        public String dependency;
        public String exclusion;
        public String extend;
    }

    public static void main(String[] args) throws Exception {
        Path modulesPath = args.length > 0 ? Paths.get(args[0]) : Paths.get("../attempt/modules.txt").normalize();
        Path temoPath    = args.length > 1 ? Paths.get(args[1]) : Paths.get("../attempt/src-gen/temo.json").normalize();
        Path demoPath    = args.length > 2 ? Paths.get(args[2]) : Paths.get("../attempt/src-gen/demo.json").normalize();
        Path semoPath    = args.length > 3 ? Paths.get(args[3]) : Paths.get("../attempt/src-gen/semo.json").normalize();
        Path outPath     = args.length > 4 ? Paths.get(args[4]) : Paths.get("../attempt/template.txt").normalize();

        // 1. 读取所有配置
        List<String> moduleNames = readModuleNames(modulesPath);
        Map<String, String> nameToTemplate = readTemplates(temoPath);
        Map<String, ModuleConstraint> moduleConstraints = readModuleConstraints(demoPath);
        Map<String, List<ParameterSpec>> moduleParams = readParameterSpecs(semoPath);

        // 2. 生成每个模块的实例
        List<String> expanded = new ArrayList<>();
        for (String moduleName : moduleNames) {
            String template = nameToTemplate.get(moduleName);
            if (template == null) {
                System.err.println("[WARN] 未在 temo.json 中找到模块: " + moduleName);
                continue;
            }

            ModuleConstraint constraint = moduleConstraints.get(moduleName);
            if (constraint == null) {
                System.err.println("[WARN] 未在 demo.json 中找到模块约束: " + moduleName);
                expanded.add(template); // 使用原始模板
                continue;
            }

            // 3. 为该模块生成一个实例（modules.txt 中的每一行对应一个实例）
            // min/max 已在 Main.java 中控制了重复次数，这里只需处理参数选择
            String instance = generateModuleInstance(moduleName, template, constraint, moduleParams);
            expanded.add(instance);
        }

        // 4. 输出结果
        expanded.forEach(System.out::println);
        
        createParentDirectories(outPath);
        Files.write(outPath, expanded, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("\n[INFO] 已写入: " + outPath.toAbsolutePath());
    }

    /**
     * 生成单个模块实例（只调整参数个数，不填充具体值）
     */
    private static String generateModuleInstance(String moduleName, String template, 
                                                  ModuleConstraint constraint,
                                                  Map<String, List<ParameterSpec>> moduleParams) {
        // 提取模块ID，如 &HEAD, &MESH 等
        String mid = template.substring(0, template.indexOf(' ')).trim();
        List<ParameterSpec> allParams = moduleParams.getOrDefault(mid, Collections.emptyList());
        
        // 如果没有参数规范，或者 val_min/val_max 都是 null，直接返回原模板
        if (allParams.isEmpty() || (constraint.val_min == null && constraint.val_max == null)) {
            return template;
        }
        
        // 分离必需和可选参数
        List<ParameterSpec> mustParams = allParams.stream()
            .filter(p -> p.isMust)
            .collect(Collectors.toList());
        
        List<ParameterSpec> optionalParams = allParams.stream()
            .filter(p -> !p.isMust)
            .collect(Collectors.toList());

        // 确定要生成的总参数数量
        int minTotal = Math.max(constraint.val_min, mustParams.size());
        int maxTotal = Math.min(constraint.val_max, allParams.size());
        int totalParams;
        
        if (maxTotal >= minTotal) {
            totalParams = RANDOM.nextInt(maxTotal - minTotal + 1) + minTotal;
        } else {
            // 如果约束不合理，使用必需参数数量
            totalParams = mustParams.size();
        }

        // 随机选择可选参数
        List<ParameterSpec> selectedParams = new ArrayList<>(mustParams);
        int optionalCount = totalParams - mustParams.size();
        if (optionalCount > 0 && !optionalParams.isEmpty()) {
            Collections.shuffle(optionalParams);
            selectedParams.addAll(optionalParams.subList(0, Math.min(optionalCount, optionalParams.size())));
        }

        // 调试：打印选中的参数ID
        String selectedIds = selectedParams.stream().map(p -> p.id).collect(Collectors.joining(", "));
        System.out.println("[DEBUG] " + moduleName + " - 必需:" + mustParams.size() + 
                           ", 范围:[" + constraint.val_min + "," + constraint.val_max + 
                           "], 选择:" + selectedParams.size() + "个参数: [" + selectedIds + "]");

        // 只保留选中的参数，不填充值
        return filterTemplate(template, selectedParams);
    }

    /**
     * 过滤模板，只保留选中的参数（不填充值，保留占位符）
     */
    private static String filterTemplate(String template, List<ParameterSpec> selectedParams) {
        Set<String> selectedIds = selectedParams.stream()
            .map(p -> p.id)
            .collect(Collectors.toSet());
        
        // 先提取模块头（如 &MISC）
        String moduleHeader = template.substring(0, template.indexOf(' ')).trim();
        
        // 解析参数部分
        StringBuilder result = new StringBuilder(moduleHeader);
        String[] parts = template.split(",");
        
        boolean hasParams = false;
        int addedCount = 0; // 记录实际添加的参数数量
        
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            
            // 移除结尾的 /
            part = part.replaceAll("/\\s*$", "").trim();
            
            // 跳过空部分
            if (part.isEmpty()) {
                continue;
            }
            
            // 提取参数名
            int eqIdx = part.indexOf('=');
            if (eqIdx > 0) {
                String rawParamId = part.substring(0, eqIdx).trim();
                // 移除模块前缀（如 &MISC GVEC -> GVEC）
                String paramId = rawParamId.replaceAll("^&\\w+\\s+", "");
                
                // 如果是选中的参数，保留
                if (selectedIds.contains(paramId)) {
                    if (hasParams) {
                        result.append(",");
                    }
                    result.append(" ").append(paramId).append("=").append(part.substring(eqIdx + 1).trim());
                    hasParams = true;
                    addedCount++;
                }
            }
        }
        
        System.out.println("[DEBUG] filterTemplate: 选中=" + selectedIds.size() + "个, 实际添加=" + addedCount + "个");
        
        // 添加结尾符
        result.append(" /");
        
        return result.toString();
    }

    // ...existing code...
    
    private static List<String> readModuleNames(Path modulesPath) throws IOException {
        try (Stream<String> lines = Files.lines(modulesPath, StandardCharsets.UTF_8)) {
            return lines
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .filter(s -> !s.startsWith("#") && !s.startsWith("//"))
                    .map(s -> {
                        int idx = s.indexOf(':');
                        return (idx >= 0 ? s.substring(0, idx) : s).trim();
                    })
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        }
    }

    private static Map<String, String> readTemplates(Path temoPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<TemplateEntry> list = mapper.readValue(Files.readAllBytes(temoPath),
                new TypeReference<List<TemplateEntry>>() {});
        Map<String, String> map = new HashMap<>();
        for (TemplateEntry e : list) {
            if (e != null && e.name != null) {
                map.put(e.name.trim(), e.val == null ? "" : e.val);
            }
        }
        return map;
    }

    private static Map<String, ModuleConstraint> readModuleConstraints(Path demoPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<ModuleConstraint> list = mapper.readValue(Files.readAllBytes(demoPath),
                new TypeReference<List<ModuleConstraint>>() {});
        return list.stream().collect(Collectors.toMap(c -> c.name, c -> c));
    }

    private static Map<String, List<ParameterSpec>> readParameterSpecs(Path semoPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<ParameterSpec> list = mapper.readValue(Files.readAllBytes(semoPath),
                new TypeReference<List<ParameterSpec>>() {});
        return list.stream().collect(Collectors.groupingBy(p -> p.mid));
    }

    private static void createParentDirectories(Path outPath) throws IOException {
        Path parent = outPath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
    }
}
