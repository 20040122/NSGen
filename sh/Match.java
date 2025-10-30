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
    public static class Entry {
        public String name;
        public String val;
    }

    public static void main(String[] args) throws Exception {
        
        Path modulesPath = args.length > 0 ? Paths.get(args[0]) : Paths.get("../attempt/modules.txt").normalize();
        Path jsonPath    = args.length > 1 ? Paths.get(args[1]) : Paths.get("../attempt/src-gen/temo.json").normalize();
        Path outPath     = args.length > 2 ? Paths.get(args[2]) : Paths.get("../attempt/template.txt").normalize();

        List<String> moduleNames = readModuleNames(modulesPath);

        Map<String, String> nameToVal = readNameToVal(jsonPath);

        List<String> expanded = new ArrayList<>();
        for (String name : moduleNames) {
            String val = nameToVal.get(name);
            if (val != null) {
                expanded.add(val);
            } else {
                System.err.println("[WARN] 未在 JSON 中找到模块: " + name);
            }
        }

  
        expanded.forEach(System.out::println);

        createParentDirectories(outPath);
        Files.write(outPath, expanded, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        System.out.println("\n[INFO] 已写入: " + outPath.toAbsolutePath());
        System.out.println("[INFO] modules.txt: " + modulesPath.toAbsolutePath());
        System.out.println("[INFO] temo.json:   " + jsonPath.toAbsolutePath());
    }

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

    private static Map<String, String> readNameToVal(Path jsonPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<Entry> list = mapper.readValue(Files.readAllBytes(jsonPath),
                new TypeReference<List<Entry>>() {});
        Map<String, String> map = new HashMap<>();
        for (Entry e : list) {
            if (e != null && e.name != null) {
                map.put(e.name.trim(), e.val == null ? "" : e.val);
            }
        }
        return map;
    }

    private static void createParentDirectories(Path outPath) throws IOException {
        Path parent = outPath.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
    }
}
