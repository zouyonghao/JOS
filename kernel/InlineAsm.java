package kernel;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface InlineAsm {
    String value();
    String constraints();
}
