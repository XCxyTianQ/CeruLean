import java.io.*;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * CeruLean 复现校验工具 —— 零第三方依赖（JDK 17+ 单文件运行）。
 *
 * 设计目标：把「上游 jar 是可复现的、我们的发行物只在白名单补丁处偏离上游」变成一条可判定的命令。
 *
 * 子命令：
 *   upstream   复现上游 Paper 发行 jar：容器哈希 → 原版 bundler 哈希 → 内层 server jar 哈希
 *              → 补丁哈希 → 打补丁后的 outputHash，全链逐字节校验
 *   inspect    打印一个 paperclip 容器的五个清单 + MANIFEST
 *   diff       两个 jar 的类级 CRC-32 差异报告（可与白名单比对出「非预期漂移」）
 *   selftest   负向自测：篡改补丁/依赖库后必须被检出（证明校验不是空转）
 *
 * 依赖：curl（下载）。补丁器（jbsdiff）不 vendoring，而是从**已通过哈希校验的**官方容器内提取，
 *       与 Paperclip 官方运行时行为一致；供应链加固方案见 tools/verify/README.md。
 */
public final class CeruLeanVerify {

    static final String FILL = "https://fill.papermc.io/v3";
    static final String PATCHER_CLASS = "paperclip.libs.io.sigpipe.jbsdiff.Patch";

    static Path cache() {
        String env = System.getenv("CERULEAN_CACHE");
        return Paths.get(env != null && !env.isEmpty() ? env
                : System.getProperty("user.home") + "/.cerulean-cache");
    }

    // ───────────────────────── 基础工具 ─────────────────────────

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String sha256(byte[] b) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    static String sha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    /** 跑外部命令；quiet=true 时不把输出打到终端。 */
    static int exec(List<String> cmd, boolean quiet) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (quiet) pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        if (!quiet) p.getInputStream().transferTo(System.out);
        return p.waitFor();
    }

    static String curlText(String url) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("curl", "-fsSL", "--retry", "3",
                "--connect-timeout", "20", url).redirectErrorStream(true);
        Process p = pb.start();
        String body = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0) throw new IOException("curl 失败 rc=" + rc + " url=" + url + "\n" + head(body, 400));
        return body;
    }

    static String head(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    /** 带缓存下载：目标已存在且非空则直接复用。 */
    static void download(String url, Path dest) throws Exception {
        if (Files.exists(dest) && Files.size(dest) > 0) return;
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        int rc = exec(List.of("curl", "-fSL", "--retry", "3", "--connect-timeout", "20",
                "-o", tmp.toString(), url), true);
        if (rc != 0 || !Files.exists(tmp) || Files.size(tmp) == 0)
            throw new IOException("下载失败 rc=" + rc + " url=" + url);
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    static String readEntry(Path jar, String name) throws Exception {
        try (ZipFile zf = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry e = zf.getEntry(name);
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    static byte[] readEntryBytes(Path jar, String name) throws Exception {
        try (ZipFile zf = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry e = zf.getEntry(name);
            if (e == null) return null;
            try (InputStream in = zf.getInputStream(e)) { return in.readAllBytes(); }
        }
    }

    static boolean hasEntry(Path jar, String name) throws Exception {
        try (ZipFile zf = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            return zf.getEntry(name) != null;
        }
    }

    /** name -> {uncompressedSize, crc32} */
    static Map<String, long[]> index(Path jar) throws Exception {
        Map<String, long[]> m = new TreeMap<>();
        try (ZipFile zf = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                m.put(e.getName(), new long[]{e.getSize(), e.getCrc()});
            }
        }
        return m;
    }

    static void extractEntry(Path jar, String name, Path dest) throws Exception {
        try (ZipFile zf = new ZipFile(jar.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry e = zf.getEntry(name);
            if (e == null) throw new IOException("容器内缺少条目: " + name);
            Files.createDirectories(dest.getParent());
            try (InputStream in = zf.getInputStream(e)) {
                Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** 把 zip 复制一份，并把某个条目的内容翻转两位（用于负向自测）。 */
    static void copyZipTampered(Path src, Path dst, String entryName) throws Exception {
        boolean hit = false;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(src), StandardCharsets.UTF_8);
             ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(dst), StandardCharsets.UTF_8)) {
            ZipEntry e;
            byte[] buf = new byte[1 << 16];
            while ((e = in.getNextEntry()) != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                byte[] data = bos.toByteArray();
                if (e.getName().equals(entryName) && data.length > 4) {
                    data[data.length / 2] ^= 0x01;
                    data[data.length / 3] ^= 0x02;
                    hit = true;
                }
                ZipEntry ne = new ZipEntry(e.getName());
                out.putNextEntry(ne);
                out.write(data);
                out.closeEntry();
            }
        }
        if (!hit) throw new IOException("自测失败：未找到要篡改的条目 " + entryName);
    }

    // ───────────────────────── 极简 JSON ─────────────────────────

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObj(Object o) { return (Map<String, Object>) o; }
    @SuppressWarnings("unchecked")
    static List<Object> asArr(Object o) { return (List<Object>) o; }
    static String str(Object o, String k) {
        Object v = asObj(o).get(k);
        return v == null ? null : String.valueOf(v);
    }
    static long num(Object o, String k) {
        Object v = asObj(o).get(k);
        return v instanceof Number ? ((Number) v).longValue() : -1;
    }

    static Object parseJson(String s) {
        P p = new P(s);
        Object v = p.value();
        return v;
    }

    static final class P {
        final String s; int i;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws();
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': i += 4; return Boolean.TRUE;
                case 'f': i += 5; return Boolean.FALSE;
                case 'n': i += 4; return null;
                default: return number();
            }
        }
        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = string(); ws(); i++; // ':'
                m.put(k, value()); ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
            }
        }
        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value()); ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
            }
        }
        String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                        default: sb.append(e);
                    }
                } else sb.append(c);
            }
        }
        Object number() {
            int st = i;
            while (i < s.length() && "-+.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
            return Long.parseLong(t);
        }
    }

    // ───────────────────────── 校验报告 ─────────────────────────

    static final class Report {
        final List<String[]> rows = new ArrayList<>();
        boolean ok = true;
        void check(String label, boolean pass, String detail) {
            rows.add(new String[]{pass ? "PASS" : "FAIL", label, detail});
            if (!pass) ok = false;
        }
        void info(String label, String detail) { rows.add(new String[]{"INFO", label, detail}); }
        void print() {
            int w = 0;
            for (String[] r : rows) w = Math.max(w, r[1].length());
            for (String[] r : rows) {
                System.out.printf("  [%s] %-" + w + "s  %s%n", r[0], r[1], r[2]);
            }
            System.out.println();
            System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
            if (!ok) {
                System.out.println("失败项：");
                for (String[] r : rows) if (r[0].equals("FAIL")) System.out.println("  - " + r[1] + " :: " + r[2]);
            }
        }
        List<String> failures() {
            List<String> f = new ArrayList<>();
            for (String[] r : rows) if (r[0].equals("FAIL")) f.add(r[1] + " :: " + r[2]);
            return f;
        }
    }

    // ───────────────────────── 补丁器（从已校验容器内加载） ─────────────────────────

    static Method loadPatcher(Path paperJar, Path work) throws Exception {
        Path libDir = work.resolve("patcher-classes");
        if (!Files.exists(libDir)) {
            Files.createDirectories(libDir);
            try (ZipFile zf = new ZipFile(paperJar.toFile(), StandardCharsets.UTF_8)) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory() || !e.getName().startsWith("paperclip/libs/")) continue;
                    Path out = libDir.resolve(e.getName());
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zf.getInputStream(e)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        URLClassLoader cl = new URLClassLoader(new URL[]{libDir.toUri().toURL()},
                ClassLoader.getPlatformClassLoader());
        Class<?> c = Class.forName(PATCHER_CLASS, true, cl);
        return c.getMethod("patch", byte[].class, byte[].class, OutputStream.class);
    }

    // ───────────────────────── 清单解析 ─────────────────────────

    static final class ChainEntry {
        String location, originalHash, patchHash, outputHash, originalPath, patchPath, outputPath;
    }

    static List<ChainEntry> parsePatchesList(String text) {
        List<ChainEntry> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            String[] f = line.split("\t");
            if (f.length < 7) continue;
            ChainEntry e = new ChainEntry();
            e.location = f[0]; e.originalHash = f[1]; e.patchHash = f[2]; e.outputHash = f[3];
            e.originalPath = f[4]; e.patchPath = f[5]; e.outputPath = f[6];
            out.add(e);
        }
        return out;
    }

    static List<String[]> parseThreeCol(String text) {
        List<String[]> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            String[] f = line.split("\t");
            if (f.length >= 3) out.add(f);
        }
        return out;
    }

    // ───────────────────────── 核心：复现校验 ─────────────────────────

    static Report verify(Path paperJar, String expectedContainerSha256, boolean apply, boolean skipVanilla)
            throws Exception {
        Report r = new Report();
        Path work = cache().resolve("work");
        Files.createDirectories(work);

        r.info("容器", paperJar.getFileName() + "  (" + Files.size(paperJar) + " B)");
        String csha = sha256(paperJar);
        if (expectedContainerSha256 != null) {
            r.check("容器 sha256", csha.equalsIgnoreCase(expectedContainerSha256),
                    csha.equalsIgnoreCase(expectedContainerSha256) ? csha : "期望 " + expectedContainerSha256 + " 实际 " + csha);
        } else {
            r.info("容器 sha256", csha);
        }

        String patchesList = readEntry(paperJar, "META-INF/patches.list");
        String versionsList = readEntry(paperJar, "META-INF/versions.list");
        String libsList = readEntry(paperJar, "META-INF/libraries.list");
        String dlCtx = readEntry(paperJar, "META-INF/download-context");
        String mainClass = readEntry(paperJar, "META-INF/main-class");
        r.check("容器清单完整", patchesList != null && versionsList != null && libsList != null && dlCtx != null,
                "patches.list=" + (patchesList != null) + " versions.list=" + (versionsList != null)
                        + " libraries.list=" + (libsList != null) + " download-context=" + (dlCtx != null));
        if (patchesList == null || versionsList == null || dlCtx == null) return r;

        List<ChainEntry> entries = parsePatchesList(patchesList);
        List<String[]> versions = parseThreeCol(versionsList);
        List<String[]> libs = parseThreeCol(libsList);
        r.info("清单规模", "补丁链 " + entries.size() + " 条 · 版本 " + versions.size()
                + " 条 · 依赖库 " + libs.size() + " 条 · 服务端入口 " + mainClass);

        // 1) 内嵌补丁条目哈希
        for (ChainEntry e : entries) {
            byte[] patch = readEntryBytes(paperJar, "META-INF/versions/" + e.patchPath);
            boolean exists = patch != null;
            String actual = exists ? sha256(patch) : "缺失";
            r.check("补丁哈希 " + e.patchPath, exists && actual.equalsIgnoreCase(e.patchHash),
                    exists ? (actual.equalsIgnoreCase(e.patchHash) ? actual : "期望 " + e.patchHash + " 实际 " + actual)
                           : "容器内没有该补丁条目");
        }

        // 2) 内嵌依赖库哈希
        int checkedLibs = 0, missingLibs = 0, failedLibs = 0;
        for (String[] l : libs) {
            String path = l[2], want = l[0];
            String entry = "META-INF/libraries/" + path;
            if (!hasEntry(paperJar, entry)) { missingLibs++; continue; }
            byte[] b = readEntryBytes(paperJar, entry);
            String actual = sha256(b);
            checkedLibs++;
            if (!actual.equalsIgnoreCase(want)) {
                failedLibs++;
                r.check("依赖库哈希 " + path, false, "期望 " + want + " 实际 " + actual);
            }
        }
        r.check("内嵌依赖库哈希", failedLibs == 0,
                "校验 " + checkedLibs + " 个，失败 " + failedLibs + " 个；未内嵌(启动时取自原版 bundler) " + missingLibs + " 个");

        if (skipVanilla) return r;

        // 3) 原版 bundler
        String[] ctx = dlCtx.trim().split("\t");
        String vanillaHash = ctx[0], vanillaUrl = ctx[1];
        Path vanilla = cache().resolve("vanilla-" + vanillaHash.substring(0, 16) + ".jar");
        download(vanillaUrl, vanilla);
        String vsha = sha256(vanilla);
        r.check("原版 bundler sha256", vsha.equalsIgnoreCase(vanillaHash),
                vsha.equalsIgnoreCase(vanillaHash) ? vanilla + "  " + vsha : "期望 " + vanillaHash + " 实际 " + vsha);

        // 4) 逐条复现补丁链
        Method patcher = apply ? loadPatcher(paperJar, work) : null;
        for (ChainEntry e : entries) {
            String inEntry = e.location.equals("versions")
                    ? "META-INF/versions/" + e.originalPath
                    : "META-INF/libraries/" + e.originalPath;
            Path orig = cache().resolve("orig-" + e.originalHash.substring(0, 16) + ".jar");
            if (!Files.exists(orig)) {
                if (!hasEntry(vanilla, inEntry)) {
                    r.check("补丁输入 " + e.originalPath, false, "原版 bundler 内没有 " + inEntry);
                    continue;
                }
                extractEntry(vanilla, inEntry, orig);
            }
            String osha = sha256(orig);
            r.check("补丁输入哈希 " + e.originalPath, osha.equalsIgnoreCase(e.originalHash),
                    osha.equalsIgnoreCase(e.originalHash) ? osha : "期望 " + e.originalHash + " 实际 " + osha);

            if (!apply) continue;
            byte[] patchBytes = readEntryBytes(paperJar, "META-INF/versions/" + e.patchPath);
            Path out = work.resolve("out-" + e.outputHash.substring(0, 16) + ".jar");
            try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
                patcher.invoke(null, Files.readAllBytes(orig), patchBytes, os);
            }
            String outSha = sha256(out);
            r.check("输出哈希 " + e.outputPath, outSha.equalsIgnoreCase(e.outputHash),
                    outSha.equalsIgnoreCase(e.outputHash) ? outSha + "  (" + Files.size(out) + " B)"
                            : "期望 " + e.outputHash + " 实际 " + outSha);
        }
        return r;
    }

    // ───────────────────────── 子命令 ─────────────────────────

    static Map<String, String> argMap(String[] args, int from) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = from; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String k = args[i].substring(2);
                String v = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
                m.put(k, v);
            } else m.put("_", args[i]);
        }
        return m;
    }

    static int cmdUpstream(String[] args) throws Exception {
        Map<String, String> a = argMap(args, 1);
        String version = a.get("version");
        if (version == null) { System.out.println("用法: upstream --version 26.3 [--build 175] [--channel STABLE] [--jar <本地jar>] [--no-apply]"); return 2; }

        Path paperJar;
        String expectContainer = null;
        String buildDesc = "";
        if (a.containsKey("jar")) {
            paperJar = Paths.get(a.get("jar"));
            buildDesc = "本地文件 " + paperJar;
        } else {
            String api = FILL + "/projects/paper/versions/" + version + "/builds";
            Object root = parseJson(curlText(api));
            List<Object> builds = asArr(root);
            String wantBuild = a.get("build"), wantChannel = a.getOrDefault("channel", "STABLE");
            Object pick = null;
            for (Object b : builds) {
                if (wantBuild != null) {
                    if (String.valueOf(num(b, "id")).equals(wantBuild)) { pick = b; break; }
                } else if (wantChannel.equalsIgnoreCase(str(b, "channel"))) {
                    if (pick == null || num(b, "id") > num(pick, "id")) pick = b;
                }
            }
            if (pick == null) { System.out.println("未找到匹配构建"); return 2; }
            Map<String, Object> dl = asObj(asObj(pick).get("downloads")).isEmpty() ? null
                    : asObj(asObj(pick).get("downloads"));
            Object srv = asObj(pick).get("downloads") == null ? null
                    : asObj(asObj(pick).get("downloads")).get("server:default");
            String url = str(srv, "url");
            expectContainer = str(asObj(asObj(srv).get("checksums")), "sha256");
            buildDesc = "build " + num(pick, "id") + " / " + str(pick, "channel") + " / " + str(pick, "time");
            paperJar = cache().resolve(expectContainer + ".jar");
            System.out.println("→ 上游构建: " + buildDesc);
            System.out.println("→ 下载: " + url);
            download(url, paperJar);
        }

        System.out.println("→ 复现校验: " + buildDesc);
        System.out.println();
        boolean apply = !a.containsKey("no-apply");
        Report r = verify(paperJar, expectContainer, apply, a.containsKey("skip-vanilla"));
        r.print();
        if (a.containsKey("report")) {
            StringBuilder sb = new StringBuilder("{\n  \"jar\": \"" + paperJar + "\",\n  \"ok\": " + r.ok + ",\n  \"checks\": [\n");
            for (int i = 0; i < r.rows.size(); i++) {
                String[] row = r.rows.get(i);
                sb.append("    {\"status\":\"").append(row[0]).append("\",\"check\":\"")
                  .append(row[1].replace("\"", "\\\"")).append("\",\"detail\":\"")
                  .append(row[2].replace("\\", "\\\\").replace("\"", "\\\"")).append("\"}")
                  .append(i + 1 < r.rows.size() ? "," : "").append("\n");
            }
            sb.append("  ]\n}\n");
            Files.writeString(Paths.get(a.get("report")), sb.toString());
            System.out.println("报告已写入 " + a.get("report"));
        }
        return r.ok ? 0 : 1;
    }

    static int cmdInspect(String[] args) throws Exception {
        if (args.length < 2) { System.out.println("用法: inspect <jar>"); return 2; }
        Path jar = Paths.get(args[1]);
        Map<String, long[]> idx = index(jar);
        long classes = idx.keySet().stream().filter(k -> k.endsWith(".class")).count();
        System.out.println("容器: " + jar + "  (" + Files.size(jar) + " B, " + idx.size() + " 条目, " + classes + " 个 class)");
        System.out.println("sha256: " + sha256(jar));
        for (String n : new String[]{"META-INF/main-class", "META-INF/download-context",
                "META-INF/versions.list", "META-INF/patches.list"}) {
            String v = readEntry(jar, n);
            System.out.println("\n── " + n + " ──");
            System.out.println(v == null ? "(无)" : v.trim());
        }
        String libs = readEntry(jar, "META-INF/libraries.list");
        System.out.println("\n── META-INF/libraries.list ── (" + (libs == null ? 0 : libs.trim().split("\n").length) + " 条)");
        System.out.println("MANIFEST:");
        String mf = readEntry(jar, "META-INF/MANIFEST.MF");
        System.out.println(mf == null ? "(无)" : mf.trim());
        return 0;
    }

    static int cmdDiff(String[] args) throws Exception {
        Map<String, String> a = argMap(args, 3);
        if (args.length < 3) { System.out.println("用法: diff <jarA> <jarB> [--top 15] [--json out.json]"); return 2; }
        Path A = Paths.get(args[1]), B = Paths.get(args[2]);
        Map<String, long[]> ia = index(A), ib = index(B);
        int top = a.containsKey("top") ? Integer.parseInt(a.get("top")) : 15;

        List<String> added = new ArrayList<>(), removed = new ArrayList<>(), changedSame = new ArrayList<>();
        int identical = 0;
        for (Map.Entry<String, long[]> e : ib.entrySet()) {
            long[] x = ia.get(e.getKey());
            if (x == null) { if (e.getKey().endsWith(".class")) added.add(e.getKey()); continue; }
            if (x[1] == e.getValue()[1]) identical++;
            else if (e.getKey().endsWith(".class")) changedSame.add(e.getKey());
        }
        for (String k : ia.keySet()) if (!ib.containsKey(k) && k.endsWith(".class")) removed.add(k);

        System.out.println("A = " + A.getFileName() + "   (" + ia.size() + " 条目)");
        System.out.println("B = " + B.getFileName() + "   (" + ib.size() + " 条目)");
        System.out.println();
        System.out.println("类：新增 " + added.size() + " · 删除 " + removed.size()
                + " · 同路径字节不同 " + changedSame.size() + " · 同路径字节相同 " + identical);
        System.out.println("全部文件：条目差 " + (ib.size() - ia.size()));

        Map<String, Integer> roots = new TreeMap<>();
        for (String k : added) {
            String[] p = k.split("/");
            String root = p.length > 1 ? p[0] + "/" + p[1] : p[0];
            roots.merge(root, 1, Integer::sum);
        }
        System.out.println("\n新增类的根分布 (top " + top + "):");
        roots.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(top)
             .forEach(e -> System.out.printf("  %5d  %s%n", e.getValue(), e.getKey()));

        List<String[]> deltas = new ArrayList<>();
        for (String k : changedSame) deltas.add(new String[]{k, String.valueOf(ib.get(k)[0] - ia.get(k)[0])});
        deltas.sort((x, y) -> Long.compare(Math.abs(Long.parseLong(y[1])), Math.abs(Long.parseLong(x[1]))));
        System.out.println("\n体积变化最大的类 (top " + top + "):");
        for (int i = 0; i < Math.min(top, deltas.size()); i++) {
            String k = deltas.get(i)[0];
            System.out.printf("  %+9d B  %s%n", Long.parseLong(deltas.get(i)[1]), k);
        }
        if (a.containsKey("json")) {
            StringBuilder sb = new StringBuilder("{\"a\":\"" + A + "\",\"b\":\"" + B + "\",\"added\":" + added.size()
                    + ",\"removed\":" + removed.size() + ",\"changed\":" + changedSame.size()
                    + ",\"identical\":" + identical + "}");
            Files.writeString(Paths.get(a.get("json")), sb.toString());
            System.out.println("\nJSON 报告: " + a.get("json"));
        }
        return 0;
    }

    static int cmdSelftest(String[] args) throws Exception {
        Map<String, String> a = argMap(args, 1);
        String version = a.getOrDefault("version", "26.3");
        String build = a.get("build");
        Path work = cache().resolve("work");
        Files.createDirectories(work);

        // 取一个官方容器（优先复用缓存）
        Path official;
        if (a.containsKey("jar")) official = Paths.get(a.get("jar"));
        else {
            Object root = parseJson(curlText(FILL + "/projects/paper/versions/" + version + "/builds"));
            List<Object> builds = asArr(root);
            Object pick = null;
            for (Object b : builds) {
                if (build != null) { if (String.valueOf(num(b, "id")).equals(build)) { pick = b; break; } }
                else if ("STABLE".equalsIgnoreCase(str(b, "channel")) && (pick == null || num(b, "id") > num(pick, "id"))) pick = b;
            }
            if (pick == null) { System.out.println("未找到构建"); return 2; }
            Object srv = asObj(asObj(pick).get("downloads")).get("server:default");
            String sha = str(asObj(asObj(srv).get("checksums")), "sha256");
            official = cache().resolve(sha + ".jar");
            download(str(srv, "url"), official);
        }

        System.out.println("负向自测（期望：完好容器 PASS，被篡改容器 FAIL）");
        System.out.println();
        boolean pass = true;

        System.out.println("① 未篡改容器（只查容器内部哈希）");
        Report r0 = verify(official, null, false, true);
        r0.print();
        pass &= r0.ok;

        String patchEntry;
        List<ChainEntry> entries = parsePatchesList(readEntry(official, "META-INF/patches.list"));
        patchEntry = "META-INF/versions/" + entries.get(0).patchPath;
        Path t1 = work.resolve("tampered-patch.jar");
        copyZipTampered(official, t1, patchEntry);
        System.out.println("\n② 篡改补丁条目 " + patchEntry);
        Report r1 = verify(t1, null, false, true);
        r1.print();
        pass &= !r1.ok;

        String libPath = null;
        for (String[] l : parseThreeCol(readEntry(official, "META-INF/libraries.list"))) {
            if (hasEntry(official, "META-INF/libraries/" + l[2])) { libPath = l[2]; break; }
        }
        Path t2 = work.resolve("tampered-lib.jar");
        copyZipTampered(official, t2, "META-INF/libraries/" + libPath);
        System.out.println("\n③ 篡改内嵌依赖库 " + libPath);
        Report r2 = verify(t2, null, false, true);
        r2.print();
        pass &= !r2.ok;

        System.out.println();
        System.out.println(pass ? "SELFTEST: PASS（校验器能检出篡改，不是空转）" : "SELFTEST: FAIL");
        return pass ? 0 : 1;
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("CeruLean Verify —— 上游复现与漂移校验");
            System.out.println("  upstream --version 26.3 [--build N|--channel STABLE] [--jar f] [--no-apply] [--report f.json]");
            System.out.println("  inspect <jar>");
            System.out.println("  diff <jarA> <jarB> [--top N] [--json f]");
            System.out.println("  selftest [--version 26.3] [--build N]");
            System.out.println("缓存目录: " + cache() + "（可用 CERULEAN_CACHE 覆盖）");
            return;
        }
        int rc = switch (args[0]) {
            case "upstream" -> cmdUpstream(args);
            case "inspect" -> cmdInspect(args);
            case "diff" -> cmdDiff(args);
            case "selftest" -> cmdSelftest(args);
            default -> { System.out.println("未知子命令: " + args[0]); yield 2; }
        };
        System.exit(rc);
    }
}
