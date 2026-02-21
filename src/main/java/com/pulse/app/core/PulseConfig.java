package com.pulse.app.core;

import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record PulseConfig(
        int port,
        long retentionMs,
        double sampleRate,
        long slowQueryThresholdMs,
        long slowHttpThresholdMs,
        String bindAddress,
        String appName
) {

    public static PulseConfig fromAgentArgs(String args) {
        Map<String, String> map = parseKeyValueArgs(args);
        int port = parseInt(map.get("port"), 17321);
        long retentionMs = parseLong(map.get("retentionMs"), 15 * 60 * 1000L);
        double sampleRate = parseDouble(map.get("sampleRate"), 1.0D);
        long slowThreshold = parseLong(map.get("slowMs"), 300L);
        long slowHttpThreshold = parseLong(map.get("slowHttpMs"), 500L);
        String bindAddress = map.getOrDefault("bind", "127.0.0.1");
        String detectedApp = detectRunningAppName();
        String explicitAppName = map.get("appName");
        String appName = (detectedApp == null || detectedApp.isBlank())
                ? firstNonBlank(explicitAppName, "Monitored Application")
                : detectedApp;
        return new PulseConfig(port, retentionMs, sampleRate, slowThreshold, slowHttpThreshold, bindAddress, appName);
    }

    public static String detectRunningAppName() {
        String springName = System.getProperty("spring.application.name");
        if (springName != null && !springName.isBlank()) {
            return springName;
        }

        String fromCommand = detectFromJavaCommand();
        if (fromCommand != null && !fromCommand.isBlank()) {
            return fromCommand;
        }

        String fromRuntime = detectFromRuntime();
        if (fromRuntime != null && !fromRuntime.isBlank()) {
            return fromRuntime;
        }

        String fromPom = detectFromPomXml();
        if (fromPom != null && !fromPom.isBlank()) {
            return fromPom;
        }

        return "Monitored Application";
    }

    private static String detectFromJavaCommand() {
        String command = System.getProperty("sun.java.command");
        if (command == null || command.isBlank()) {
            return null;
        }
        String[] tokens = command.trim().split("\\s+");
        if (tokens.length == 0) {
            return null;
        }
        String firstToken = tokens[0];
        if (firstToken.endsWith(".jar")) {
            return stripJarExtension(fileName(firstToken));
        }
        if ("org.springframework.boot.loader.launch.JarLauncher".equals(firstToken)
                || "org.springframework.boot.loader.JarLauncher".equals(firstToken)
                || "org.springframework.boot.loader.PropertiesLauncher".equals(firstToken)) {
            if (tokens.length > 1 && tokens[1].endsWith(".jar")) {
                return stripJarExtension(fileName(tokens[1]));
            }
            return null;
        }
        if (firstToken.contains(".")) {
            return firstToken;
        }
        return null;
    }

    private static String detectFromRuntime() {
        String mainClass = detectMainClass();
        String artifactId = detectArtifactId();
        String groupId = deriveGroupId(mainClass);

        if (groupId != null && !groupId.isBlank() && artifactId != null && !artifactId.isBlank()) {
            return groupId + ":" + artifactId;
        }
        if (artifactId != null && !artifactId.isBlank()) {
            return artifactId;
        }
        return null;
    }

    private static String detectMainClass() {
        String command = System.getProperty("sun.java.command");
        if (command == null || command.isBlank()) {
            return null;
        }
        String firstToken = command.trim().split("\\s+")[0];
        if (firstToken.endsWith(".jar")) {
            return null;
        }
        if (!firstToken.contains(".")) {
            return null;
        }
        return firstToken;
    }

    private static String detectArtifactId() {
        String classPath = System.getProperty("java.class.path");
        if (classPath != null && !classPath.isBlank()) {
            String[] entries = classPath.split(Pattern.quote(System.getProperty("path.separator", ":")));
            for (String entry : entries) {
                if (entry == null || entry.isBlank()) {
                    continue;
                }
                String normalized = entry.replace("\\\\", "/");
                int index = normalized.indexOf("/target/classes");
                if (index > 0) {
                    String root = normalized.substring(0, index);
                    int slash = root.lastIndexOf('/');
                    if (slash >= 0 && slash + 1 < root.length()) {
                        return root.substring(slash + 1);
                    }
                }
            }
        }

        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            try {
                Path path = Path.of(userDir).normalize().getFileName();
                if (path != null) {
                    return path.toString();
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String deriveGroupId(String mainClass) {
        if (mainClass == null || mainClass.isBlank()) {
            return null;
        }
        int lastDot = mainClass.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String pkg = mainClass.substring(0, lastDot);
        pkg = pkg.replaceAll("(\\.api|\\.app|\\.application)$", "");
        String[] parts = pkg.split("\\.");
        if (parts.length >= 2) {
            return parts[0] + "." + parts[1];
        }
        return pkg;
    }

    private static String fileName(String pathLike) {
        String normalized = pathLike.replace("\\\\", "/");
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            return normalized.substring(slash + 1);
        }
        return normalized;
    }

    private static String stripJarExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        if (fileName.toLowerCase().endsWith(".jar")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    private static String detectFromPomXml() {
        try {
            String userDir = System.getProperty("user.dir");
            if (userDir == null || userDir.isBlank()) {
                return null;
            }
            Path pomPath = Path.of(userDir, "pom.xml");
            if (!Files.exists(pomPath)) {
                return null;
            }

            String xml = Files.readString(pomPath);
            String projectBlock = extractProjectBlock(xml);
            String parentBlock = firstTagBlock(projectBlock, "parent");
            String projectWithoutParent = removeFirstTagBlock(projectBlock, "parent");

            String groupId = firstTagValue(projectWithoutParent, "groupId");
            String artifactId = firstTagValue(projectWithoutParent, "artifactId");

            if (groupId == null || groupId.isBlank()) {
                groupId = firstTagValue(parentBlock, "groupId");
            }

            if (artifactId == null || artifactId.isBlank()) {
                return null;
            }
            if (groupId == null || groupId.isBlank()) {
                return artifactId;
            }
            return groupId + ":" + artifactId;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String extractProjectBlock(String xml) {
        if (xml == null) {
            return "";
        }
        int start = xml.indexOf("<project");
        if (start < 0) {
            return xml;
        }
        int startClose = xml.indexOf('>', start);
        int end = xml.lastIndexOf("</project>");
        if (startClose < 0 || end < 0 || end <= startClose) {
            return xml;
        }
        return xml.substring(startClose + 1, end);
    }

    private static String firstTagBlock(String xml, String tag) {
        if (xml == null) {
            return "";
        }
        Pattern pattern = Pattern.compile("<" + tag + ">([\\s\\S]*?)</" + tag + ">");
        Matcher matcher = pattern.matcher(xml);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static String removeFirstTagBlock(String xml, String tag) {
        if (xml == null) {
            return "";
        }
        return xml.replaceFirst("<" + tag + ">[\\s\\S]*?</" + tag + ">", "");
    }

    private static String firstTagValue(String xml, String tag) {
        if (xml == null) {
            return null;
        }
        Pattern pattern = Pattern.compile("<" + tag + ">([^<]+)</" + tag + ">");
        Matcher matcher = pattern.matcher(xml);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private static Map<String, String> parseKeyValueArgs(String args) {
        Map<String, String> result = new HashMap<>();
        if (args == null || args.isBlank()) {
            return result;
        }
        String[] pairs = args.split(",");
        for (String pair : pairs) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty() || !trimmed.contains("=")) {
                continue;
            }
            String[] tokens = trimmed.split("=", 2);
            result.put(tokens[0].trim(), tokens[1].trim());
        }
        return result;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
