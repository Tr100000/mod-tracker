package io.github.tr100000.modtracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ModFilters {
    private ModFilters() {}

    public static final List<String> BLACKLIST = new ArrayList<>();
    public static final List<String> WHITELIST = new ArrayList<>();

    private static List<Pattern> BLACKLIST_PATTERNS;
    private static List<Pattern> WHITELIST_PATTERNS;

    public static void compilePatterns() {
        BLACKLIST_PATTERNS = BLACKLIST.stream().map(Pattern::compile).collect(Collectors.toUnmodifiableList());
        WHITELIST_PATTERNS = WHITELIST.stream().map(Pattern::compile).collect(Collectors.toUnmodifiableList());
    }

    public static void clearCompiledPatterns() {
        BLACKLIST_PATTERNS = Collections.emptyList();
        WHITELIST_PATTERNS = Collections.emptyList();
    }

    public static boolean filter(String modid) {
        return BLACKLIST_PATTERNS.stream().noneMatch(pattern -> pattern.matcher(modid).matches()) || WHITELIST_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(modid).matches());
    }
}
