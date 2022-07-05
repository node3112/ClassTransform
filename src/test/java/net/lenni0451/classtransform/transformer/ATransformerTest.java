package net.lenni0451.classtransform.transformer;

import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.targets.IInjectionTarget;
import net.lenni0451.classtransform.test.SCalculator;
import net.lenni0451.classtransform.test.VCalculator;
import net.lenni0451.classtransform.utils.ASMUtils;
import net.lenni0451.classtransform.utils.tree.BasicClassProvider;
import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.junit.jupiter.api.BeforeEach;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Field;
import java.util.Map;

public abstract class ATransformerTest {

    protected IClassProvider classProvider;
    protected TransformerManager transformerManager;
    protected Map<String, IInjectionTarget> injectionTargets;
    protected ClassNode staticCalculatorClass;
    protected ClassNode virtualCalculatorClass;

    @BeforeEach
    public void setUp() {
        this.classProvider = new BasicClassProvider();
        this.transformerManager = new TransformerManager(this.classProvider);
        try {
            Field f = TransformerManager.class.getDeclaredField("injectionTargets");
            f.setAccessible(true);
            this.injectionTargets = (Map<String, IInjectionTarget>) f.get(this.transformerManager);
        } catch (Throwable t) {
            throw new RuntimeException("Unable to get injection targets", t);
        }
        this.staticCalculatorClass = ASMUtils.fromBytes(this.classProvider.getClass(SCalculator.class.getName()));
        this.virtualCalculatorClass = ASMUtils.fromBytes(this.classProvider.getClass(VCalculator.class.getName()));
    }

    protected ClassNode getTransformerClass(final String name) {
        return ASMUtils.fromBytes(this.classProvider.getClass(name));
    }

}
