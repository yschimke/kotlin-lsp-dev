package smoke.typehierarchy;

/**
 * A Java subclass of the Kotlin `Base` in the type-hierarchy fixture.
 *
 * This is the case the feature's core goes through Kotlin light classes to support, and it cannot
 * be expressed in the single-Kotlin-file FIXTURE. Shipped as a smoke/project/ file so the check
 * can require it among the subtypes.
 */
public class JavaCircle extends Base {
    @Override
    public double area() {
        return 2.0;
    }
}
