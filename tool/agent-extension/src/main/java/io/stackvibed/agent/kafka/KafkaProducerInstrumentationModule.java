package io.stackvibed.agent.kafka;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasClassesNamed;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.util.List;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Records the value and headers of a sent record on the OTel agent's PRODUCER span. The hook is the private
 * {@code KafkaProducer.doSend(ProducerRecord, Callback)}, which {@code send} calls inside the agent's own
 * {@code send} advice scope: the producer span is current there whatever order the two advices are applied in, and
 * the record already carries the injected {@code traceparent} and {@code baggage} headers.
 */
public class KafkaProducerInstrumentationModule extends InstrumentationModule {

    private static final String KAFKA_PRODUCER = "org.apache.kafka.clients.producer.KafkaProducer";

    public KafkaProducerInstrumentationModule() {
        super("stack-kafka-producer", "stack");
    }

    @Override
    public ElementMatcher.Junction<ClassLoader> classLoaderMatcher() {
        return hasClassesNamed(KAFKA_PRODUCER);
    }

    // No muzzle code generation in this build: helpers referenced by advice are listed explicitly.
    @Override
    public List<String> getAdditionalHelperClassNames() {
        return List.of("io.stackvibed.agent.BoundedValueSerializer", "io.stackvibed.agent.kafka.KafkaCapture");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return List.of(new ProducerInstrumentation());
    }

    public static class ProducerInstrumentation implements TypeInstrumentation {

        @Override
        public ElementMatcher<TypeDescription> typeMatcher() {
            return named(KAFKA_PRODUCER);
        }

        @Override
        public void transform(TypeTransformer transformer) {
            transformer.applyAdviceToMethod(
                    named("doSend")
                            .and(takesArguments(2))
                            .and(takesArgument(0, named("org.apache.kafka.clients.producer.ProducerRecord")))
                            .and(takesArgument(1, named("org.apache.kafka.clients.producer.Callback"))),
                    KafkaProducerInstrumentationModule.class.getName() + "$DoSendAdvice");
        }
    }

    public static class DoSendAdvice {

        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.Argument(0) ProducerRecord<?, ?> record) {
            KafkaCapture.recordProduce(record);
        }
    }
}
