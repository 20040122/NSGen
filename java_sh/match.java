package com.example.attempt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class Match {

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Rule {
        public String name;
        public Boolean required;
        public Integer min, max, val_min, val_max;
        int minOr0() { return min == null ? 0 : min; }
        boolean hasMax() { return max != null; }
    }

   
    private static final Path OUTPUT_DIR =
            Paths.get("D:\\runtime-EclipseApplication\\attempt");

    static List<Rule> loadRules(String... externalPathOpt) throws Exception {
        try (InputStream cp = Main.class.getResourceAsStream("/demo.json")) {
            if (cp != null) {
                return Arrays.asList(new ObjectMapper().readValue(cp, Rule[].class));
            }
        }
        Path p;
        if (externalPathOpt != null && externalPathOpt.length > 0 && externalPathOpt[0] != null) {
            p = Paths.get(externalPathOpt[0]);
        } else {
            p = Paths.get(System.getProperty("user.dir")).resolve("../attempt/demo.json").normalize();
        }
        try (InputStream in = new FileInputStream(p.toFile())) {
            return Arrays.asList(new ObjectMapper().readValue(in, Rule[].class));
        }
    }

    static int randInRange(int lo, int hi) {
        if (hi < lo) { int t = lo; lo = hi; hi = t; }
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    public static void main(String[] args) throws Exception {
        List<Rule> rules = loadRules(args.length > 0 ? args[0] : null);

        List<String> lines = new ArrayList<>();

        for (Rule r : rules) {
            if (Boolean.TRUE.equals(r.required)) {
                lines.add(r.name + " : ");
            } else if (r.hasMax()) {
                int times = randInRange(r.minOr0(), r.max);
                for (int i = 0; i < times; i++) lines.add(r.name + " : ");
            } else {
                int times = randInRange(r.minOr0(), 3); 
                for (int i = 0; i < times; i++) lines.add(r.name + " : ");
            }
        }

        lines.forEach(System.out::println);
        Files.createDirectories(OUTPUT_DIR);

        Path txt = OUTPUT_DIR.resolve("../attempt/modules.txt");
        Files.write(txt, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("[Saved] " + txt.toAbsolutePath());
    }
}

