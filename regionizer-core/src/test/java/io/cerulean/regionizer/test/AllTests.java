package io.cerulean.regionizer.test;

/** 全部测试入口（纯 JDK，直接 java 运行）。 */
public final class AllTests {

    private AllTests() {
    }

    public static void main(final String[] args) {
        final T t = new T();
        System.out.println("CeruLean regionizer-core 测试");
        InvariantTests.run(t);
        OwnershipTests.run(t);
        ModelCheckTests.run(t);
        System.exit(t.summary());
    }
}
