package nmj.rule;

import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.beans.Introspector;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class RuleContext<Model> {

    private static final Logger log = LoggerFactory.getLogger(RuleContext.class);
    private static final Map<String, String> METHOD_NAME_2_FIELD_NAME_CACHE = new ConcurrentHashMap<>(8192);
    private final Rules<Model> rules;
    private final Model model;
    private final Map<String, InfoCache.ModelInst> modelInst;
    private final InfoCache.ValueMap valueMap;
    private final Lazy<Model> proxy;

    private RuleContext(Rules<Model> rules, Consumer<Model> consumer) {
        this.rules = rules;
        InfoCache.DefaultCons<Model> cons = InfoCache.getConstructor(this.rules.getClass());
        this.model = cons.newInstance();
        consumer.accept(this.model);
        this.proxy = new Lazy<>(() -> {
            Enhancer enhancer = new Enhancer();
            enhancer.setSuperclass(cons.getDeclaringClass());
            enhancer.setCallback(new RuleCallback<>(this));
            return (Model) enhancer.create();
        });
        this.modelInst = InfoCache.getModelInst(this.rules.getClass());
        this.valueMap = new InfoCache.ValueMap(modelInst.size() * 2);
    }

    public static <Model> RuleContext<Model> create(Rules<Model> rule, Consumer<Model> consumer) {
        if (rule == null || consumer == null) {
            throw new IllegalArgumentException();
        }
        return new RuleContext<>(rule, consumer);
    }

    /**
     * 获取代理对象, 用于快捷(强类型的方式)获取模型字段
     *
     * @return
     */
    public Model getProxy() {
        return proxy.get();
    }

    public <T> T getOrElse(String name, T orElse) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException();
        }
        final InfoCache.ValueHolder valueHolder = get(name, new HashSet<>(128));
        if (valueHolder.isComplete()) {
            return valueHolder.getValue();
        }
        return orElse;
    }

    public <T> T getOrNull(String name) {
        return getOrElse(name, null);
    }

    private InfoCache.ValueHolder get(String name, Set<String> visited) {
        final InfoCache.ModelInst modelInst = this.modelInst.get(name);
        if (modelInst == null) {
            throw new IllegalArgumentException();
        }
        final InfoCache.ValueHolder valueHolder = valueMap.get(name, model, modelInst);
        if (valueHolder.isComplete()) {
            return valueHolder;
        }
        visited.add(name);
        for (InfoCache.RuleInst instRule : modelInst.getRules()) {
            final List<InfoCache.ModelInst> args = instRule.getArgs();
            final int size = args.size();
            if (size == 0) {
                Object value = instRule.getValue(rules);
                valueHolder.setValue(value);
                if (valueHolder.isComplete()) {
                    return valueHolder;
                }
                continue;
            }
            boolean success = true;
            final Object[] arguments = new Object[size];
            for (int i = 0; i < size; i++) {
                final InfoCache.ModelInst arg = args.get(i);
                final String argName = arg.getName();
                final InfoCache.ValueHolder argValue = valueMap.get(argName, model, arg);
                if (argValue.isComplete()) {
                    arguments[i] = argValue.getValue();
                    continue;
                }
                if (visited.contains(argName)) {
                    // 死循环了, 标记为不可达后, 立即跳出
                    success = false;
                    break;
                }
                // 尝试获取值(递归调用)
                get(argName, visited);
                if (argValue.isComplete()) {
                    arguments[i] = argValue.getValue();
                    continue;
                }
                success = false;
                break;
            }
            // 存在这种情况的, 所有又加个这个看起来啰嗦的判断
            if (valueHolder.isComplete()) {
                return valueHolder;
            }
            if (success) {
                final Object value = instRule.getValue(rules, arguments);
                valueHolder.setValue(value);
                if (valueHolder.isComplete()) {
                    return valueHolder;
                }
            }
        }
        return valueHolder;
    }

    private <T> T getOrNull(Method method) {
        final String name = method.getName();
        if (name.startsWith("set")) {
            throw new UnsupportedOperationException();
        }
        String fieldName = getFieldName(name);
        if (fieldName == null) {
            log.warn("{} fieldName not found!", method.toGenericString());
            return null;
        }
        return getOrNull(fieldName);
    }

    private String getFieldName(String methodName) {
        String fieldName = METHOD_NAME_2_FIELD_NAME_CACHE.get(methodName);
        if (fieldName != null) {
            return fieldName;
        }
        if (methodName.startsWith("get")) {
            fieldName = Introspector.decapitalize(methodName.substring(3));
        } else if (methodName.startsWith("is")) {
            fieldName = Introspector.decapitalize(methodName.substring(3));
        } else {
            return null;
        }
        METHOD_NAME_2_FIELD_NAME_CACHE.put(methodName, fieldName);
        return fieldName;
    }

    /**
     * 懒加载指定对象
     *
     * @param <Model>
     */
    private static final class Lazy<Model> {
        private final Supplier<Model> supplier;
        private Optional<Model> value;

        public Lazy(Supplier<Model> supplier) {
            this.supplier = supplier;
            this.value = null;
        }

        public Model get() {
            if (value == null) {
                value = Optional.ofNullable(supplier.get());
            }
            return value.orElse(null);
        }
    }

    private static final class RuleCallback<Model> implements MethodInterceptor {

        private final RuleContext<Model> context;

        public RuleCallback(RuleContext<Model> context) {
            this.context = context;
        }

        @Override
        public Object intercept(Object o, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
            return context.getOrNull(method);
        }
    }
}
