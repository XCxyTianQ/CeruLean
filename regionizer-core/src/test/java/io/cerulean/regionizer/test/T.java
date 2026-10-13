package io.cerulean.regionizer.test;

import java.util.ArrayList;
import java.util.List;

/** 零依赖测试夹具（本机不引 JUnit：无 gradle、无外网依赖）。 */
public final class T {

    private int passed;
    private int failed;
    private final List<String> failures = new ArrayList<>();
    private String group = "-";

    public void group(final String name) {
        this.group = name;
        System.out.println("\n── " + name);
    }

    public void check(final String name, final boolean cond) {
        this.check(name, cond, "");
    }

    public void check(final String name, final boolean cond, final String detail) {
        if (cond) {
            this.passed++;
            System.out.println("   ✓ " + name);
        } else {
            this.failed++;
            final String line = this.group + " / " + name + (detail.isEmpty() ? "" : "  [" + detail + ']');
            this.failures.add(line);
            System.out.println("   ✗ " + name + (detail.isEmpty() ? "" : "  [" + detail + ']'));
        }
    }

    public void eq(final String name, final Object expect, final Object actual) {
        final boolean ok = expect == null ? actual == null : expect.equals(actual);
        this.check(name, ok, "期望=" + expect + " 实际=" + actual);
    }

    /** 收集到的违规列表为空即通过。 */
    public void noViolations(final String name, final List<String> violations) {
        this.check(name, violations.isEmpty(), violations.isEmpty() ? "" : violations.get(0)
                + (violations.size() > 1 ? " (+" + (violations.size() - 1) + ")" : ""));
    }

    public int summary() {
        System.out.println("\n════════════════════════════════════════");
        System.out.println("  通过 " + this.passed + " / 失败 " + this.failed);
        if (!this.failures.isEmpty()) {
            System.out.println("  失败明细:");
            for (final String f : this.failures) {
                System.out.println("    - " + f);
            }
        }
        System.out.println("════════════════════════════════════════");
        return this.failed == 0 ? 0 : 1;
    }
}
