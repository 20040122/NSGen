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
    }

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

        List<String> lines = Files.readAllLines(templatePath, StandardCharsets.UTF_8);
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) out.add(replaceLine(line, idx));
        Files.write(outPath, out, StandardCharsets.UTF_8);
    }
    
    private static String replaceLine(String line, Map<String, Map<String, Rule>> idx) {
        Matcher m = MID_AT_LINE_START.matcher(line);
        if (!m.find()) return line; 
        String mid = m.group(1).toUpperCase();

        StringBuffer sb = new StringBuffer();
        Matcher ph = PLACEHOLDER_IN_LINE.matcher(line);
        while (ph.find()) {
            String id = ph.group(1).toUpperCase();
            Rule rule = Optional.ofNullable(idx.get(mid)).map(map -> map.get(id)).orElse(null);
            String replacement;
            if (rule == null) {
                replacement = ph.group(0);
            } else {
                replacement = id + "=" + randomValueFor(rule, id);
            }
            ph.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        ph.appendTail(sb);
        return sb.toString();
    }

    private static String randomValueFor(Rule r, String id) {
        int dim = (r.dimension != null && r.dimension > 0)
                ? r.dimension
                : DEFAULT_DIM_BY_ID.getOrDefault(id.toUpperCase(), 1);

        if (r.valueType != null && r.valueType.trim().equalsIgnoreCase("<enum>")) {
            String choice = randomFromStrings(r.scale);
            return quoteIfNeeded(choice);
        }

        if (isNumericRange(r.scale)) {
            double min = toDouble(r.scale.get(0));
            double max = toDouble(r.scale.get(1));
            if (dim > 1) {
                List<String> arr = new ArrayList<>(dim);
                for (int i = 0; i < dim; i++) {
                    arr.add(formatNumber(randomBetween(min, max, r.valueType)));
                }
                return String.join(", ", arr) + ",";
            } else {
                return formatNumber(randomBetween(min, max, r.valueType));
            }
        }

        if (r.scale != null && !r.scale.isEmpty()) {
            Object pick = r.scale.get(ThreadLocalRandom.current().nextInt(r.scale.size()));
            if (pick instanceof Number) {
                return formatNumber(((Number) pick).doubleValue());
            } else {
                return quoteIfNeeded(String.valueOf(pick).replace("'", ""));
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
    private static String quoteIfNeeded(String s) {
        return s;
    }
}
