package io.stackvibed.agent.jdbc;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.implementsInterface;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.takesNoArguments;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.extension.instrumentation.internal.ExperimentalInstrumentationModule;
import java.sql.PreparedStatement;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;

/**
 * Records the parameters bound to prepared statements and, for updates, the row count, on the JDBC
 * client span that OTel's {@code jdbc} instrumentation creates around {@code execute*()}.
 *
 * <p>{@link #order()} is greater than the {@code jdbc} module's (0): modules are applied in order and
 * the first applied advice ends up outermost, so this advice runs inside OTel's, while its span is
 * current.
 */
public class JdbcParametersInstrumentationModule extends InstrumentationModule
        implements ExperimentalInstrumentationModule {

    private static final String PREPARED_STATEMENT = "java.sql.PreparedStatement";

    public JdbcParametersInstrumentationModule() {
        super("stack-jdbc-parameters", "stack");
    }

    @Override
    public int order() {
        return 1;
    }

    @Override
    public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
        return hasClassesNamed(PREPARED_STATEMENT);
    }

    // No muzzle code generation in this build: helpers and virtual fields are registered explicitly.
    @Override
    public List<String> getAdditionalHelperClassNames() {
        return List.of("io.stackvibed.agent.BoundedValueSerializer", "io.stackvibed.agent.jdbc.ParameterRecorder");
    }

    @Override
    public void registerVirtualFields(VirtualFieldRegistration registration) {
        registration.register(PREPARED_STATEMENT, "java.util.Map");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new PreparedStatementInstrumentation());
    }

    public static class PreparedStatementInstrumentation implements TypeInstrumentation {

        @Override
        public ElementMatcher<ClassLoader> classLoaderOptimization() {
            return hasClassesNamed(PREPARED_STATEMENT);
        }

        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return implementsInterface(named(PREPARED_STATEMENT));
        }

        @Override
        public void transform(TypeTransformer transformer) {
            String advice = JdbcParametersInstrumentationModule.class.getName();
            // The parameter setters declared by PreparedStatement (index first, value second).
            transformer.applyAdviceToMethod(
                    namedOneOf(
                                    "setNull", "setBoolean", "setByte", "setShort", "setInt", "setLong",
                                    "setFloat", "setDouble", "setBigDecimal", "setString", "setNString",
                                    "setBytes", "setDate", "setTime", "setTimestamp", "setObject", "setURL",
                                    "setRowId", "setRef", "setArray", "setBlob", "setClob", "setNClob",
                                    "setSQLXML", "setAsciiStream", "setUnicodeStream", "setBinaryStream",
                                    "setCharacterStream", "setNCharacterStream")
                            .and(isPublic())
                            .and(takesArgument(0, int.class))
                            .and(takesArguments(2).or(takesArguments(3)).or(takesArguments(4))),
                    advice + "$SetParameterAdvice");
            transformer.applyAdviceToMethod(
                    named("clearParameters").and(isPublic()).and(takesNoArguments()),
                    advice + "$ClearParametersAdvice");
            // Batches are left out: the bound values only describe the last row added.
            transformer.applyAdviceToMethod(
                    namedOneOf("execute", "executeQuery", "executeUpdate", "executeLargeUpdate")
                            .and(isPublic())
                            .and(takesNoArguments()),
                    advice + "$ExecuteAdvice");
        }
    }

    public static class SetParameterAdvice {

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(
                @Advice.This PreparedStatement statement,
                @Advice.Origin("#m") String method,
                @Advice.Argument(0) int index,
                @Advice.Argument(value = 1, typing = Assigner.Typing.DYNAMIC) Object value) {
            ParameterRecorder.record(statement, method, index, value);
        }
    }

    public static class ClearParametersAdvice {

        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.This PreparedStatement statement) {
            ParameterRecorder.clear(statement);
        }
    }

    public static class ExecuteAdvice {

        @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
        public static void onExit(
                @Advice.This PreparedStatement statement,
                @Advice.Origin("#m") String method,
                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                @Advice.Thrown Throwable thrown) {
            ParameterRecorder.onExecute(statement, method, result, thrown);
        }
    }
}
