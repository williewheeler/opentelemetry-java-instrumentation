/*
 * Copyright The OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opentelemetry.auto.instrumentation.jdbc;

import static io.opentelemetry.auto.instrumentation.jdbc.JdbcTracer.TRACER;
import static io.opentelemetry.auto.tooling.ClassLoaderMatcher.hasClassesNamed;
import static io.opentelemetry.auto.tooling.bytebuddy.matcher.AgentElementMatchers.implementsInterface;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import io.opentelemetry.auto.tooling.Instrumenter;
import io.opentelemetry.context.Scope;
import io.opentelemetry.instrumentation.auto.api.CallDepthThreadLocalMap.Depth;
import io.opentelemetry.trace.Span;
import java.sql.Statement;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class StatementInstrumentation extends Instrumenter.Default {

  public StatementInstrumentation() {
    super("jdbc");
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("java.sql.Statement");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("java.sql.Statement"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".normalizer.Token",
      packageName + ".normalizer.ParseException",
      packageName + ".normalizer.SimpleCharStream",
      packageName + ".normalizer.SqlNormalizerConstants",
      packageName + ".normalizer.TokenMgrError",
      packageName + ".normalizer.SqlNormalizerTokenManager",
      packageName + ".normalizer.SqlNormalizer",
      packageName + ".JDBCMaps",
      packageName + ".JDBCUtils",
      packageName + ".JdbcTracer",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        nameStartsWith("execute").and(takesArgument(0, String.class)).and(isPublic()),
        StatementInstrumentation.class.getName() + "$StatementAdvice");
  }

  public static class StatementAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(0) final String sql,
        @Advice.This final Statement statement,
        @Advice.Local("otelSpan") Span span,
        @Advice.Local("otelScope") Scope scope,
        @Advice.Local("otelCallDepth") Depth callDepth) {

      callDepth = TRACER.getCallDepth();
      if (callDepth.getAndIncrement() == 0) {
        span = TRACER.startSpan(statement, sql);
        if (span != null) {
          scope = TRACER.startScope(span);
        }
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void stopSpan(
        @Advice.Thrown final Throwable throwable,
        @Advice.Local("otelSpan") Span span,
        @Advice.Local("otelScope") Scope scope,
        @Advice.Local("otelCallDepth") Depth callDepth) {
      if (callDepth.decrementAndGet() == 0 && scope != null) {
        scope.close();
        if (throwable == null) {
          TRACER.end(span);
        } else {
          TRACER.endExceptionally(span, throwable);
        }
      }
    }
  }
}
