package nmj.rule.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注在方法上, 表明其为一条规则; 注意规则的变量名要和模型名称一一对应
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Rule {

    /**
     * 此规则作用的模型名称
     *
     * @return
     */
    String value();
}
